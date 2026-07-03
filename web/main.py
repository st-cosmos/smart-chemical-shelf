"""웹 개발 기초 — LED 상태 조회 및 스마트 저울.

프론트엔드(static/)와 REST API를 같은 FastAPI 서버가 함께 제공합니다.
- GET /api/led 로 현재 LED 상태 조회 (Read-only)
- ESP32/ESP8266 보드에서 가변저항 값을 받거나 GET /api/potentiometer로 상태 조회
"""

from datetime import datetime
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

app = FastAPI()

# 서버가 들고 있는 LED 상태 (서버를 끄면 초기화됩니다)
leds = {
    "led1": {"on": False, "by": "아직 아무도", "time": "-"}
}



# 가변저항 및 무게 시뮬레이터 상태
potentiometer = {
    "value": 0,
    "previous_value": 0,
    "status": "유지",  # "증가", "감소", "유지"
    "time": "-"
}





class LedCommand(BaseModel):
    on: bool
    by: str


class PotentiometerEvent(BaseModel):
    value: int


# --- REST API (정적 파일 mount 보다 먼저 등록해야 우선 적용됩니다) ---
@app.get("/api/led")
def get_led():
    """현재 LED의 상태와 마지막으로 바꾼 사람을 돌려줍니다."""
    return {
        "led1": leds["led1"],
        "led1_on": leds["led1"]["on"]
    }


@app.put("/api/led/{led_id}")
def set_led(led_id: str, cmd: LedCommand):
    """특정 LED 상태를 바꾸고, 누가 언제 바꿨는지 기록합니다."""
    if led_id not in leds:
        return {"error": "Invalid LED ID"}
    leds[led_id]["on"] = cmd.on
    leds[led_id]["by"] = cmd.by
    leds[led_id]["time"] = datetime.now().strftime("%H:%M:%S")
    return leds





@app.post("/api/potentiometer")
def post_potentiometer(event: PotentiometerEvent):
    """가변저항 값을 전달받아 상태(증가/감소/유지)를 갱신합니다."""
    global potentiometer
    prev_val = potentiometer["value"]
    current_val = event.value
    
    # 아날로그 입력 특성상 미세한 변동이 있을 수 있으나, 
    # 증가/감소를 확실히 계산하기 위해 직접 비교합니다.
    if current_val > prev_val:
        status = "증가"
    elif current_val < prev_val:
        status = "감소"
    else:
        status = "유지"
        
    now_str = datetime.now().strftime("%H:%M:%S")
    potentiometer["previous_value"] = prev_val
    potentiometer["value"] = current_val
    potentiometer["status"] = status
    potentiometer["time"] = now_str
    
    return {"status": "success", "data": potentiometer}


@app.get("/api/potentiometer")
def get_potentiometer():
    """현재 가변저항 상태와 값을 반환합니다."""
    return potentiometer


# --- 웹 페이지(static/) 를 "/" 에 서빙 ---
# html=True 이면 "/" 요청 시 static/index.html 을 돌려줍니다.
STATIC_DIR = Path(__file__).parent / "static"
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
