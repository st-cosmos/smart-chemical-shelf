from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from database import get_db
import device_status
import models
import schemas
from datetime import datetime

router = APIRouter(prefix="/api/shelves", tags=["shelves"])

@router.get("", response_model=List[schemas.ShelfResponse])
def get_shelves(db: Session = Depends(get_db)):
    """등록된 선반은 항상 반환하고, 미등록 기기는 실제로 접속 중(하트비트가
    살아 있는 경우)일 때만 노출한다. 꺼진 기기의 잔여 행이 앱/웹의
    '신규 선반 기기 감지' 알림을 계속 띄우는 문제를 막는다."""
    shelves = db.query(models.Shelf).all()
    return [
        s for s in shelves
        if s.status == "registered" or device_status.is_online(s.id)
    ]

@router.get("/configs")
def get_shelf_configs(db: Session = Depends(get_db)):
    configs = db.query(models.ShelfConfig).all()
    return configs

@router.post("/configs/{shelf_id}")
def update_shelf_config(shelf_id: str, req: Dict[str, int], db: Session = Depends(get_db)):
    cfg = db.query(models.ShelfConfig).filter(models.ShelfConfig.id == shelf_id).first()
    if not cfg:
        cfg = models.ShelfConfig(id=shelf_id, name=f"선반 {shelf_id}", rows=3, cols=3)
        db.add(cfg)
    
    if "rows" in req:
        cfg.rows = req["rows"]
    if "cols" in req:
        cfg.cols = req["cols"]
        
    db.commit()
    db.refresh(cfg)
    return cfg

@router.post("/register/{shelf_id}", response_model=schemas.ShelfResponse)
def register_shelf(shelf_id: str, req: schemas.ShelfRegister, db: Session = Depends(get_db)):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == shelf_id).first()
    if not shelf:
        # If it doesn't exist, create it as registered
        shelf = models.Shelf(id=shelf_id)
        db.add(shelf)
    
    shelf.name = req.name
    shelf.parent_shelf = req.parent_shelf
    shelf.row = req.row
    shelf.col = req.col
    shelf.status = "registered"
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    
    db.commit()
    db.refresh(shelf)
    return shelf

@router.post("/{shelf_id}/weight")
def update_weight(shelf_id: str, req: schemas.WeightUpdate, db: Session = Depends(get_db)):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == shelf_id).first()
    if not shelf:
        raise HTTPException(status_code=404, detail="선반을 찾을 수 없습니다.")
        
    prev_w = shelf.weight
    shelf.prev_weight = prev_w
    shelf.weight = req.weight
    if req.battery is not None:
        shelf.battery = req.battery
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    
    db.commit()
    
    # Check if this weight update finishes an active checkin session
    # Let's import checkin_session from main or handle it centrally.
    # To keep routes clean, we can import checkin_session from a shared module
    # or handle it in the main.py or a session file.
    # Let's handle it by importing a shared state from web.main or web.session.
    # Let's query if there is an active checkin session in the DB or a global variable.
    # We will import the session from main or handle it here by accessing a global dictionary.
    from session_store import checkin_session
    
    session_result = None
    if checkin_session["active"] and checkin_session["chemical_name"]:
        # If weight increased
        if req.weight > prev_w:
            chem_name = checkin_session["chemical_name"]
            username = checkin_session.get("username", "알수없음")
            
            # Find user's nickname
            user = db.query(models.User).filter(models.User.username == username).first()
            operator_name = user.nickname if user else username
            
            # Register chemical in DB
            new_chem = models.Chemical(
                id=f"chem_{int(datetime.now().timestamp())}",
                name=chem_name,
                shelf_id=shelf_id,
                shelf_row=shelf.row or 1,
                shelf_col=shelf.col or 1,
                weight=round(req.weight, 2),
                current_status="비치중",
                time_in=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                manufacturer="시그마알드리치" # default
            )
            db.add(new_chem)
            
            # Turn off recommendation LEDs
            recommended_shelves = db.query(models.Shelf).filter(models.Shelf.led_message.like("%기존 반입%")).all()
            for r_shelf in recommended_shelves:
                r_shelf.led_on = False
                r_shelf.led_message = ""
            
            # Record log
            log = models.Log(
                chemical_id=new_chem.id,
                chemical_name=new_chem.name,
                action="반입",
                operator_name=operator_name,
                details=f"신규 반입 완료: 선반 {shelf.parent_shelf} · {shelf.row}행 {shelf.col}열에 적재됨"
            )
            db.add(log)
            
            # Reset session
            checkin_session["active"] = False
            checkin_session["chemical_name"] = ""
            checkin_session["start_time"] = 0.0
            checkin_session["username"] = ""
            
            db.commit()
            
            session_result = {
                "event": "checkin_complete",
                "chemical": {
                    "id": new_chem.id,
                    "name": new_chem.name,
                    "shelf_id": new_chem.shelf_id,
                    "shelf_row": new_chem.shelf_row,
                    "shelf_col": new_chem.shelf_col,
                    "weight": new_chem.weight
                }
            }
            
    db.commit()
    db.refresh(shelf)
    return {
        "status": "success",
        "shelf": shelf,
        "session_result": session_result
    }

@router.post("/{shelf_id}/led")
def update_led(shelf_id: str, req: schemas.LedUpdate, db: Session = Depends(get_db)):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == shelf_id).first()
    if not shelf:
        raise HTTPException(status_code=404, detail="선반을 찾을 수 없습니다.")
        
    shelf.led_on = req.led_on
    shelf.led_message = req.led_message
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    
    db.commit()
    db.refresh(shelf)
    return shelf
