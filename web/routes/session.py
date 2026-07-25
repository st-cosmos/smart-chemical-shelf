from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
import time

import checkout_flow
from database import get_db
from session_store import checkin_session, checkout_session

router = APIRouter(prefix="/api/checkin-session", tags=["session"])
checkout_router = APIRouter(prefix="/api/checkout-session", tags=["session"])


@router.get("")
def get_checkin_session():
    if checkin_session["active"]:
        elapsed = time.time() - checkin_session["start_time"]
        if elapsed > checkin_session["timeout_seconds"]:
            # Timeout has occurred
            checkin_session["active"] = False
            checkin_session["chemical_name"] = ""
            checkin_session["start_time"] = 0.0
            checkin_session["username"] = ""
            return {
                "active": False,
                "chemical_name": "",
                "time_left": 0.0,
                "timeout": True,
            }
        return {
            "active": True,
            "chemical_name": checkin_session["chemical_name"],
            "time_left": max(0.0, round(checkin_session["timeout_seconds"] - elapsed, 1)),
            "timeout": False,
        }
    return {
        "active": False,
        "chemical_name": "",
        "time_left": 0.0,
        "timeout": False,
    }


@router.post("/cancel")
def cancel_checkin_session():
    checkin_session["active"] = False
    checkin_session["chemical_name"] = ""
    checkin_session["start_time"] = 0.0
    checkin_session["username"] = ""
    return {"status": "success"}


# --- 반출 세션 (무게 감소 확정 대기) ---

@checkout_router.get("")
def get_checkout_session(db: Session = Depends(get_db)):
    """반출 세션 상태 폴링. event(경고)는 1회 전달 후 소거되고,
    result(확정 결과)는 다음 세션 시작 전까지 유지된다."""
    if checkout_session["active"] and checkout_flow.is_expired():
        checkout_flow.expire(db)
        return {
            "active": False,
            "chemical_name": "",
            "time_left": 0.0,
            "timeout": True,
            "event": None,
            "result": None,
        }

    event = checkout_session["last_event"]
    checkout_session["last_event"] = None  # 1회 전달

    if checkout_session["active"]:
        elapsed = time.time() - checkout_session["start_time"]
        return {
            "active": True,
            "chemical_name": checkout_session["chemical_name"],
            "time_left": max(0.0, round(checkout_session["timeout_seconds"] - elapsed, 1)),
            "timeout": False,
            "event": event,
            "result": None,
        }

    return {
        "active": False,
        "chemical_name": "",
        "time_left": 0.0,
        "timeout": False,
        "event": event,
        "result": checkout_session["last_result"],
    }


@checkout_router.post("/cancel")
def cancel_checkout_session(db: Session = Depends(get_db)):
    checkout_flow.cancel(db)
    return {"status": "success"}
