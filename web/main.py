import os
import json
import time
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Optional
from fastapi import FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

app = FastAPI()

# File paths for persistence
USERS_FILE = Path(__file__).parent / "users.json"
CHEMICALS_FILE = Path(__file__).parent / "chemicals.json"

# In-memory states (with defaults, loaded from files if exists)
users_db: List[Dict] = []
chemicals_db: List[Dict] = []

# OCR Chemical List (recognized by OCR system)
OCR_CHEMICALS = [
    "Ethanol",
    "Acetone",
    "Hydrochloric Acid",
    "Sodium Hydroxide",
    "Methanol",
    "Sulfuric Acid",
    "Distilled Water",
    "Benzene",
    "Toluene",
    "Hexane"
]

# 3 ESP Boards state
esp_boards: Dict[str, Dict] = {
    "ESP-01": {"id": "ESP-01", "weight": 0.0, "prev_weight": 0.0, "led_on": False, "led_message": "", "time": "-"},
    "ESP-02": {"id": "ESP-02", "weight": 0.0, "prev_weight": 0.0, "led_on": False, "led_message": "", "time": "-"},
    "ESP-03": {"id": "ESP-03", "weight": 0.0, "prev_weight": 0.0, "led_on": False, "led_message": "", "time": "-"}
}

# Check-in session state
checkin_session = {
    "active": False,
    "chemical_name": "",
    "start_time": 0.0,
    "timeout_seconds": 15.0
}

# Load data helper
def load_data():
    global users_db, chemicals_db
    if USERS_FILE.exists():
        try:
            with open(USERS_FILE, "r", encoding="utf-8") as f:
                users_db = json.load(f)
        except Exception:
            users_db = []
    else:
        users_db = []

    if CHEMICALS_FILE.exists():
        try:
            with open(CHEMICALS_FILE, "r", encoding="utf-8") as f:
                chemicals_db = json.load(f)
        except Exception:
            chemicals_db = []
    else:
        chemicals_db = []

# Save data helper
def save_users():
    with open(USERS_FILE, "w", encoding="utf-8") as f:
        json.dump(users_db, f, ensure_ascii=False, indent=2)

def save_chemicals():
    with open(CHEMICALS_FILE, "w", encoding="utf-8") as f:
        json.dump(chemicals_db, f, ensure_ascii=False, indent=2)

# Load data at startup
load_data()

# --- Pydantic Models ---
class UserRegister(BaseModel):
    username: str
    password: str

class WeightUpdate(BaseModel):
    weight: float

class LedUpdate(BaseModel):
    led_on: bool
    led_message: str = ""

class ScanInRequest(BaseModel):
    ocr_text: str

class ScanOutRequest(BaseModel):
    ocr_text: str

class SelectLedRequest(BaseModel):
    chem_id: str

# Legacy models for backwards compatibility with main.cpp
class LedCommand(BaseModel):
    on: bool
    by: str

class PotentiometerEvent(BaseModel):
    value: int
    esp_id: str = "ESP-01"

# --- REST APIs ---

# 1. User Registration APIs
@app.post("/api/users/register")
def register_user(req: UserRegister):
    # Check if user already exists
    for u in users_db:
        if u["username"] == req.username:
            raise HTTPException(status_code=400, detail="이미 존재하는 사용자 이름입니다.")
    
    new_user = {
        "username": req.username,
        "password": req.password,
        "created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    }
    users_db.append(new_user)
    save_users()
    return {"status": "success", "username": req.username}

@app.get("/api/users")
def get_users():
    return [{"username": u["username"], "created_at": u["created_at"]} for u in users_db]

# 2. ESP Status APIs
@app.get("/api/esp")
def get_esp_status():
    return esp_boards

@app.post("/api/esp/{esp_id}/weight")
def update_esp_weight(esp_id: str, req: WeightUpdate):
    if esp_id not in esp_boards:
        raise HTTPException(status_code=404, detail="ESP Board not found")
    
    board = esp_boards[esp_id]
    prev_w = board["weight"]
    new_w = req.weight
    
    # Update ESP state
    board["prev_weight"] = prev_w
    board["weight"] = new_w
    board["time"] = datetime.now().strftime("%H:%M:%S")
    
    # Check for weight increase during active check-in session
    session_result = None
    if checkin_session["active"]:
        elapsed = time.time() - checkin_session["start_time"]
        if elapsed <= checkin_session["timeout_seconds"]:
            # If weight increased
            if new_w > prev_w:
                chem_name = checkin_session["chemical_name"]
                
                # Add to chemicals list
                new_chem = {
                    "id": f"chem_{int(time.time())}",
                    "name": chem_name,
                    "esp_id": esp_id,
                    "weight": round(new_w, 2),
                    "status": "반입",
                    "time_in": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "time_out": "-"
                }
                chemicals_db.append(new_chem)
                save_chemicals()
                
                # Turn off LEDs of other shelves if recommended, set this shelf LED
                for b_id, b in esp_boards.items():
                    if b_id != esp_id and "Recommended" in b["led_message"]:
                        b["led_on"] = False
                        b["led_message"] = ""
                
                # Reset session
                checkin_session["active"] = False
                checkin_session["chemical_name"] = ""
                checkin_session["start_time"] = 0.0
                
                session_result = {
                    "event": "checkin_complete",
                    "chemical": new_chem
                }
        else:
            # Session expired
            checkin_session["active"] = False
            checkin_session["chemical_name"] = ""
            checkin_session["start_time"] = 0.0
            
    return {
        "status": "success",
        "esp_state": board,
        "session_result": session_result
    }

@app.post("/api/esp/{esp_id}/led")
def update_esp_led(esp_id: str, req: LedUpdate):
    if esp_id not in esp_boards:
        raise HTTPException(status_code=404, detail="ESP Board not found")
    
    esp_boards[esp_id]["led_on"] = req.led_on
    esp_boards[esp_id]["led_message"] = req.led_message
    esp_boards[esp_id]["time"] = datetime.now().strftime("%H:%M:%S")
    return esp_boards[esp_id]

# 3. Chemicals APIs
@app.get("/api/chemicals")
def get_chemicals():
    return chemicals_db

@app.get("/api/ocr-chemicals")
def get_ocr_chemicals():
    return OCR_CHEMICALS

@app.post("/api/chemicals/scan-in")
def scan_in_chemical(req: ScanInRequest):
    # Find matching chemical name within the ocr_text (case-insensitive)
    matched_chemical = None
    for chem in OCR_CHEMICALS:
        if chem.lower() in req.ocr_text.lower():
            matched_chemical = chem
            break
            
    if not matched_chemical:
        raise HTTPException(
            status_code=400, 
            detail=f"인식된 텍스트에서 보관 가능한 시약을 찾을 수 없습니다. (인식 대상: {', '.join(OCR_CHEMICALS)})"
        )
    
    # Check for previous check-in history of this chemical name
    has_history = False
    prev_esp_id = None
    
    # Find any history (either active "반입" or past "반출" of this chemical name)
    for c in reversed(chemicals_db):
        if c["name"] == matched_chemical:
            has_history = True
            prev_esp_id = c["esp_id"]
            break
            
    # Start check-in session
    checkin_session["active"] = True
    checkin_session["chemical_name"] = matched_chemical
    checkin_session["start_time"] = time.time()
    
    # If it has previous history, turn on that ESP's LED output
    if has_history and prev_esp_id and prev_esp_id in esp_boards:
        esp_boards[prev_esp_id]["led_on"] = True
        esp_boards[prev_esp_id]["led_message"] = f"기존 반입 이력 위치: {matched_chemical}"
        esp_boards[prev_esp_id]["time"] = datetime.now().strftime("%H:%M:%S")
        
    return {
        "status": "success",
        "chemical_name": matched_chemical,
        "has_history": has_history,
        "recommended_esp": prev_esp_id,
        "timeout_seconds": checkin_session["timeout_seconds"]
    }

@app.post("/api/chemicals/scan-out")
def scan_out_chemical(req: ScanOutRequest):
    # Find matching chemical name within the ocr_text (case-insensitive)
    matched_chemical = None
    for chem in OCR_CHEMICALS:
        if chem.lower() in req.ocr_text.lower():
            matched_chemical = chem
            break
            
    if not matched_chemical:
        raise HTTPException(
            status_code=400,
            detail=f"인식된 텍스트에서 일치하는 시약명을 찾을 수 없습니다. (인식 대상: {', '.join(OCR_CHEMICALS)})"
        )
        
    # Find an active chemical (status == "반입") with this name
    found_chem = None
    for c in reversed(chemicals_db):
        if c["name"] == matched_chemical and c["status"] == "반입":
            found_chem = c
            break
            
    if not found_chem:
        raise HTTPException(status_code=404, detail=f"반입된 시약 목록에서 '{matched_chemical}'을(를) 찾을 수 없습니다.")
        
    # Mark as checked-out
    found_chem["status"] = "반출"
    found_chem["time_out"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_chemicals()
    
    # Also turn off LED of the ESP where it was located (if it was on)
    esp_id = found_chem["esp_id"]
    if esp_id in esp_boards:
        esp_boards[esp_id]["led_on"] = False
        esp_boards[esp_id]["led_message"] = ""
        
    return {
        "status": "success",
        "chemical": found_chem
    }

@app.post("/api/chemicals/select-led")
def select_led(req: SelectLedRequest):
    # Find chemical by ID
    found_chem = None
    for c in chemicals_db:
        if c["id"] == req.chem_id:
            found_chem = c
            break
            
    if not found_chem:
        raise HTTPException(status_code=404, detail="시약을 찾을 수 없습니다.")
        
    if found_chem["status"] != "반입":
        raise HTTPException(status_code=400, detail="반입된 상태의 시약만 LED를 켤 수 있습니다.")
        
    esp_id = found_chem["esp_id"]
    if esp_id in esp_boards:
        # Toggle or turn on LED of this ESP board
        esp_boards[esp_id]["led_on"] = True
        esp_boards[esp_id]["led_message"] = f"찾는 시약 위치: {found_chem['name']}"
        esp_boards[esp_id]["time"] = datetime.now().strftime("%H:%M:%S")
        return {"status": "success", "esp_id": esp_id, "chemical_name": found_chem["name"]}
        
    raise HTTPException(status_code=500, detail="ESP 보드 상태를 찾을 수 없습니다.")

# 4. Session / Simulation APIs
@app.get("/api/checkin-session")
def get_checkin_session():
    if checkin_session["active"]:
        elapsed = time.time() - checkin_session["start_time"]
        if elapsed > checkin_session["timeout_seconds"]:
            # Timeout has occurred
            checkin_session["active"] = False
            checkin_session["chemical_name"] = ""
            checkin_session["start_time"] = 0.0
            return {
                "active": False,
                "chemical_name": "",
                "time_left": 0.0,
                "timeout": True
            }
        else:
            return {
                "active": True,
                "chemical_name": checkin_session["chemical_name"],
                "time_left": max(0.0, round(checkin_session["timeout_seconds"] - elapsed, 1)),
                "timeout": False
            }
    return {
        "active": False,
        "chemical_name": "",
        "time_left": 0.0,
        "timeout": False
    }

@app.post("/api/checkin-session/cancel")
def cancel_checkin_session():
    checkin_session["active"] = False
    checkin_session["chemical_name"] = ""
    checkin_session["start_time"] = 0.0
    return {"status": "success"}


# --- Legacy/Backwards Compatibility REST APIs for main.cpp ---
@app.get("/api/led")
def get_led():
    # Return ESP-01 LED state in the structure both main.cpp and Android App expect
    board = esp_boards["ESP-01"]
    by_val = board["led_message"] or ("서버/ESP" if board["led_on"] else "아직 아무도")
    return {
        "led1": {
            "on": board["led_on"],
            "by": by_val,
            "time": board["time"]
        },
        "led1_on": board["led_on"],
        "on": board["led_on"],
        "by": by_val,
        "time": board["time"]
    }

@app.put("/api/led/{led_id}")
def set_led(led_id: str, cmd: LedCommand):
    # Update ESP-01 LED status (or others)
    esp_id = "ESP-01"
    if led_id == "led2":
        esp_id = "ESP-02"
    elif led_id == "led3":
        esp_id = "ESP-03"
        
    esp_boards[esp_id]["led_on"] = cmd.on
    esp_boards[esp_id]["led_message"] = f"By {cmd.by}"
    esp_boards[esp_id]["time"] = datetime.now().strftime("%H:%M:%S")
    
    board = esp_boards[esp_id]
    by_val = board["led_message"] or ("서버/ESP" if board["led_on"] else "아직 아무도")
    return {
        "led1": {
            "on": esp_boards["ESP-01"]["led_on"],
            "by": esp_boards["ESP-01"]["led_message"] or ("서버/ESP" if esp_boards["ESP-01"]["led_on"] else "아직 아무도"),
            "time": esp_boards["ESP-01"]["time"]
        },
        "led1_on": esp_boards["ESP-01"]["led_on"],
        "on": board["led_on"],
        "by": by_val,
        "time": board["time"]
    }

@app.post("/api/potentiometer")
def post_potentiometer(event: PotentiometerEvent):
    # Map potentiometer event to ESP-01 weight simulation
    esp_id = event.esp_id
    if esp_id not in esp_boards:
        esp_id = "ESP-01"
        
    # Convert analog 0-1023 to float weight (0.0 - 10.0 kg)
    weight = round(event.value * 10.0 / 1023, 2)
    return update_esp_weight(esp_id, WeightUpdate(weight=weight))

@app.get("/api/potentiometer")
def get_potentiometer():
    # Map ESP-01 weight back to potentiometer format
    board = esp_boards["ESP-01"]
    raw_val = int(board["weight"] * 1023 / 10.0)
    status = "유지"
    if board["weight"] > board["prev_weight"]:
        status = "증가"
    elif board["weight"] < board["prev_weight"]:
        status = "감소"
        
    return {
        "value": raw_val,
        "previous_value": int(board["prev_weight"] * 1023 / 10.0),
        "status": status,
        "time": board["time"]
    }


# --- Serve Static Files ---
STATIC_DIR = Path(__file__).parent / "static"
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
