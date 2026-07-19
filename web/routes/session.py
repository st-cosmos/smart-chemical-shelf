from fastapi import APIRouter
import time

from session_store import checkin_session

router = APIRouter(prefix="/api/checkin-session", tags=["session"])


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
