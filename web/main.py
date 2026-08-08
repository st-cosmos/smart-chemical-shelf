import time
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI, WebSocket, WebSocketDisconnect
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


class ConnectionManager:
    def __init__(self):
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, message: dict):
        for connection in list(self.active_connections):
            try:
                await connection.send_json(message)
            except Exception:
                if connection in self.active_connections:
                    self.active_connections.remove(connection)


ws_manager = ConnectionManager()


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
    # 기존 DB 대응: create_all 은 기존 테이블에 신규 컬럼을 추가하지 않으므로 직접 보강한다.
    try:
        with engine.begin() as conn:
            for stmt in [
                "ALTER TABLE users ADD COLUMN pin VARCHAR DEFAULT '0000';",
                "ALTER TABLE chemicals ADD COLUMN incompatible_chemicals VARCHAR;",
                "ALTER TABLE chemicals ADD COLUMN incompatible_reason VARCHAR;",
            ]:
                try:
                    conn.execute(text(stmt))
                except Exception:
                    pass
            try:
                conn.execute(text("UPDATE users SET pin = '0000' WHERE pin IS NULL;"))
            except Exception:
                pass
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
app.include_router(session.checkout_router)


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await ws_manager.connect(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        ws_manager.disconnect(websocket)


# --- ESP8266 로드셀 모듈용 LED 제어 및 무게 센서 API ---


class LedCommand(BaseModel):
    on: bool


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


def _led_payload(shelf: models.Shelf):
    return {"on": bool(shelf.led_on), "time": shelf.updated_time or "-"}


def _weight_status(shelf: models.Shelf) -> str:
    if shelf.weight > shelf.prev_weight:
        return "증가"
    if shelf.weight < shelf.prev_weight:
        return "감소"
    return "유지"


@app.get("/api/led/{device_id}")
def get_led(device_id: str, db: Session = Depends(get_db)):
    """디바이스(선반 모듈)의 LED 상태를 반환합니다. 1초 주기 폴링 = 하트비트."""
    device_status.mark_seen(device_id)
    return _led_payload(_get_or_create_shelf(device_id, db))


@app.put("/api/led/{device_id}")
async def set_led(device_id: str, cmd: LedCommand, db: Session = Depends(get_db)):
    """디바이스 LED 상태를 변경하고 상태를 전달합니다."""
    shelf = _get_or_create_shelf(device_id, db)
    shelf.led_on = cmd.on
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    db.commit()
    db.refresh(shelf)
    payload = _led_payload(shelf)
    await ws_manager.broadcast({"type": "led_update", "device_id": device_id, "data": payload})
    return payload


@app.post("/api/weight/{device_id}")
async def post_weight(device_id: str, event: WeightEvent, db: Session = Depends(get_db)):
    """로드셀 무게(그램)를 전달받아 선반 상태를 갱신합니다.

    kg 단위로 환산해 선반 무게 갱신 로직에 위임하고 WebSocket으로 전송합니다.
    """
    device_status.mark_seen(device_id)
    _get_or_create_shelf(device_id, db)
    weight_kg = round(event.value / 1000.0, 3)
    result = shelves.update_weight(device_id, schemas.WeightUpdate(weight=weight_kg), db)
    shelf = result["shelf"]
    resp_data = {
        "status": "success",
        "data": {
            "value": int(shelf.weight * 1000),
            "previous_value": int(shelf.prev_weight * 1000),
            "status": _weight_status(shelf),
            "time": shelf.updated_time,
        },
        "session_result": result["session_result"],
    }
    await ws_manager.broadcast({"type": "weight_update", "device_id": device_id, "weight": shelf.weight})
    return resp_data


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
