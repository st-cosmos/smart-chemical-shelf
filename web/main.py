import time
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sqlalchemy import text
from sqlalchemy.orm import Session

import crud
import device_status
import matching
import models
import schemas
from database import Base, SessionLocal, engine, get_db
from routes import chemicals, logs, orders, session, shelves, users


def wait_for_db(retries: int = 30, delay: float = 1.0):
    """Wait for the database (postgres container) to accept connections."""
    for attempt in range(retries):
        try:
            with engine.connect():
                return
        except Exception:
            if attempt == retries - 1:
                raise
            time.sleep(delay)


@asynccontextmanager
async def lifespan(app: FastAPI):
    wait_for_db()
    # PIN 도입(8fe9e9b) 이전에 만들어진 users 테이블 대응:
    # create_all 은 기존 테이블에 컬럼을 추가하지 않으므로 직접 보강한다.
    try:
        with engine.begin() as conn:
            conn.execute(text("ALTER TABLE users ADD COLUMN IF NOT EXISTS pin VARCHAR DEFAULT '0000';"))
            conn.execute(text("UPDATE users SET pin = '0000' WHERE pin IS NULL;"))
    except Exception as e:
        print(f"Migration notice: {e}")
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        crud.init_db_seed(db)
        matching.ensure_alias_seed(db)  # 기존 DB에도 별칭 사전이 비어 있으면 시드
    finally:
        db.close()
    yield


app = FastAPI(title="Smart Chemical Shelf", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(users.router)
app.include_router(shelves.router)
app.include_router(chemicals.router)
app.include_router(orders.router)
app.include_router(logs.router)
app.include_router(session.router)


# --- ESP8266 로드셀 모듈용 디바이스 API (firmware/src/main.cpp) ---
# 펌웨어는 GET /api/device/{id} 로 LED 상태를 폴링하고,
# 무게(그램, 정수)를 POST /api/weight/{id} 로 보고합니다.
# 미지의 device_id 가 접속하면 미등록(unregistered) 선반으로 자동 생성되어
# 앱의 "신규 선반 기기 감지" 흐름으로 이어집니다.


class DeviceCommand(BaseModel):
    on: bool
    by: str


class WeightEvent(BaseModel):
    value: int  # grams


def _get_or_create_shelf(device_id: str, db: Session) -> models.Shelf:
    shelf = db.query(models.Shelf).filter(models.Shelf.id == device_id).first()
    if not shelf:
        shelf = models.Shelf(id=device_id, status="unregistered")
        db.add(shelf)
        db.commit()
        db.refresh(shelf)
    return shelf


def _device_payload(shelf: models.Shelf):
    by_val = (shelf.led_message or "서버/ESP") if shelf.led_on else "아직 아무도"
    return {"on": bool(shelf.led_on), "by": by_val, "time": shelf.updated_time or "-"}


def _weight_status(shelf: models.Shelf) -> str:
    if shelf.weight > shelf.prev_weight:
        return "증가"
    if shelf.weight < shelf.prev_weight:
        return "감소"
    return "유지"


@app.get("/api/device/{device_id}")
def get_device(device_id: str, db: Session = Depends(get_db)):
    """디바이스(선반 모듈)의 LED 상태를 돌려줍니다. 1초 주기 폴링 = 하트비트."""
    device_status.mark_seen(device_id)
    return _device_payload(_get_or_create_shelf(device_id, db))


@app.put("/api/device/{device_id}")
def set_device(device_id: str, cmd: DeviceCommand, db: Session = Depends(get_db)):
    """디바이스 LED 상태를 바꾸고, 누가 언제 바꿨는지 기록합니다."""
    shelf = _get_or_create_shelf(device_id, db)
    shelf.led_on = cmd.on
    shelf.led_message = f"By {cmd.by}"
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    db.commit()
    db.refresh(shelf)
    return _device_payload(shelf)


@app.post("/api/weight/{device_id}")
def post_weight(device_id: str, event: WeightEvent, db: Session = Depends(get_db)):
    """로드셀 무게(그램)를 전달받아 선반 상태를 갱신합니다.

    kg 단위로 환산해 선반 무게 갱신 로직(체크인 세션 완료 감지 포함)에 위임합니다.
    """
    device_status.mark_seen(device_id)
    _get_or_create_shelf(device_id, db)
    weight_kg = round(event.value / 1000.0, 3)
    result = shelves.update_weight(device_id, schemas.WeightUpdate(weight=weight_kg), db)
    shelf = result["shelf"]
    return {
        "status": "success",
        "data": {
            "value": int(shelf.weight * 1000),
            "previous_value": int(shelf.prev_weight * 1000),
            "status": _weight_status(shelf),
            "time": shelf.updated_time,
        },
        "session_result": result["session_result"],
    }


@app.get("/api/weight/{device_id}")
def get_weight(device_id: str, db: Session = Depends(get_db)):
    """디바이스의 현재 무게 상태(그램)를 반환합니다."""
    shelf = _get_or_create_shelf(device_id, db)
    return {
        "value": int(shelf.weight * 1000),
        "previous_value": int(shelf.prev_weight * 1000),
        "status": _weight_status(shelf),
        "time": shelf.updated_time or "-",
    }


# --- Serve the built React frontend ---
STATIC_DIR = Path(__file__).parent / "static"
if STATIC_DIR.exists():
    app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
