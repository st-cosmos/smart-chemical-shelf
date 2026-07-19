import time
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

import crud
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
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        crud.init_db_seed(db)
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


# --- Legacy endpoints kept for the ESP8266 firmware (firmware/src/main.cpp) ---
# The firmware polls GET /api/led and posts potentiometer values that emulate
# the load-cell weight of shelf module "ESP-01".

LEGACY_SHELF_ID = "ESP-01"


def _legacy_led_payload(db: Session):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == LEGACY_SHELF_ID).first()
    led_on = bool(shelf.led_on) if shelf else False
    by_val = (shelf.led_message if shelf and shelf.led_message else "서버/ESP") if led_on else "아직 아무도"
    led_time = shelf.updated_time if shelf else "-"
    return {
        "led1": {"on": led_on, "by": by_val, "time": led_time},
        "led1_on": led_on,
        "on": led_on,
        "by": by_val,
        "time": led_time,
    }


@app.get("/api/led")
def legacy_get_led(db: Session = Depends(get_db)):
    return _legacy_led_payload(db)


@app.put("/api/led/{led_id}")
def legacy_set_led(led_id: str, cmd: schemas.LedCommand, db: Session = Depends(get_db)):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == LEGACY_SHELF_ID).first()
    if shelf:
        shelf.led_on = cmd.on
        shelf.led_message = f"By {cmd.by}"
        shelf.updated_time = datetime.now().strftime("%H:%M:%S")
        db.commit()
    return _legacy_led_payload(db)


@app.post("/api/potentiometer")
def legacy_post_potentiometer(event: schemas.PotentiometerEvent, db: Session = Depends(get_db)):
    shelf_id = event.esp_id if event.esp_id else LEGACY_SHELF_ID
    # Convert analog 0-1023 to weight in kg (0.0 - 10.0)
    weight = round(event.value * 10.0 / 1023, 2)
    return shelves.update_weight(shelf_id, schemas.WeightUpdate(weight=weight), db)


@app.get("/api/potentiometer")
def legacy_get_potentiometer(db: Session = Depends(get_db)):
    shelf = db.query(models.Shelf).filter(models.Shelf.id == LEGACY_SHELF_ID).first()
    weight = shelf.weight if shelf else 0.0
    prev_weight = shelf.prev_weight if shelf else 0.0
    status = "유지"
    if weight > prev_weight:
        status = "증가"
    elif weight < prev_weight:
        status = "감소"
    return {
        "value": int(weight * 1023 / 10.0),
        "previous_value": int(prev_weight * 1023 / 10.0),
        "status": status,
        "time": shelf.updated_time if shelf else "-",
    }


# --- Serve the built React frontend ---
STATIC_DIR = Path(__file__).parent / "static"
if STATIC_DIR.exists():
    app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
