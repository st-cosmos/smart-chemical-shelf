from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from database import get_db
import checkout_flow
import device_status
import models
import schemas
import services.llm_safety as llm_safety
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

# 노이즈로 인한 미세 변화를 반입/반출 이벤트로 오인하지 않기 위한 최소 변화량 (kg)
MIN_EVENT_DELTA_KG = 0.05


def _handle_checkin_increase(db: Session, shelf: models.Shelf, delta_kg: float):
    """체크인 세션 중 무게 증가(안착) 처리.

    증가량(delta)을 병 무게로 기록하고, 같은 이름의 반출중 병 중
    무게가 허용 오차 내로 가장 근접한 병이 있으면 그 병을 복귀 처리해
    (동일 이름 개체 식별) 신규 행 난립을 막는다.
    """
    from session_store import checkin_session
    if not (checkin_session["active"] and checkin_session["chemical_name"]):
        return None

    chem_name = checkin_session["chemical_name"]
    username = checkin_session.get("username", "알수없음")
    user = db.query(models.User).filter(models.User.username == username).first()
    operator_name = user.nickname if user else username
    measured = round(delta_kg, 2)
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    shelf_desc = f"선반 {shelf.parent_shelf} · {shelf.row}행 {shelf.col}열"

    # 반출중인 같은 이름 병 중 실측 증가량과 가장 근접한 병 → 복귀로 판정
    restored = None
    best_diff = None
    outgone = db.query(models.Chemical).filter(
        models.Chemical.name == chem_name,
        models.Chemical.current_status == "반출중",
    ).all()
    for cand in outgone:
        if cand.weight and checkout_flow.weight_within_tolerance(cand.weight, delta_kg):
            diff = abs(cand.weight - delta_kg)
            if best_diff is None or diff < best_diff:
                best_diff, restored = diff, cand

    if restored is not None:
        restored.current_status = "비치중"
        restored.shelf_id = shelf.id
        restored.shelf_row = shelf.row or 1
        restored.shelf_col = shelf.col or 1
        restored.holder_username = None
        restored.time_in = now_str
        restored.time_out = None
        restored.weight = measured
        chem = restored
        details = f"재반입 완료: {shelf_desc}에 적재됨 (실측 {measured}kg — 기존 병 복귀)"
    else:
        chem = models.Chemical(
            id=f"chem_{int(datetime.now().timestamp())}",
            name=chem_name,
            shelf_id=shelf.id,
            shelf_row=shelf.row or 1,
            shelf_col=shelf.col or 1,
            weight=measured,
            current_status="비치중",
            time_in=now_str,
            expiration_date=checkin_session.get("expiration_date")
        )
        db.add(chem)
        db.commit()
        db.refresh(chem)
        details = f"신규 반입 완료: {shelf_desc}에 적재됨 (실측 {measured}kg)"

    # LLM 기반 혼재 금지 시약 정보 분석 및 저장
    llm_safety.ensure_chemical_incompatibility_info(chem, db)

    # 추천 위치 안내 LED 소등
    recommended = db.query(models.Shelf).filter(
        models.Shelf.led_message.like("%기존 반입%")
    ).all()
    for r_shelf in recommended:
        r_shelf.led_on = False
        r_shelf.led_message = ""

    db.add(models.Log(
        chemical_id=chem.id,
        chemical_name=chem.name,
        action="반입",
        operator_name=operator_name,
        details=details,
    ))

    # 반입 직후 인접 수납칸 혼재 금지 시약 배치 여부 검사
    safe_id, safe_desc = llm_safety.find_recommended_safe_shelf(chem, db)
    co_warning = None
    import routes.chemicals as chemical_routes
    alerts = chemical_routes.get_alerts(db)
    for warning in alerts.get("co_storage_warnings", []):
        if warning["chemical_1_id"] == chem.id or warning["chemical_2_id"] == chem.id:
            co_warning = warning
            shelf.led_on = True
            shelf.led_message = f"🚨 혼재 위험! 추천 이송: {safe_desc}"
            break

    checkin_session["active"] = False
    checkin_session["chemical_name"] = ""
    checkin_session["start_time"] = 0.0
    checkin_session["username"] = ""
    db.commit()

    return {
        "event": "checkin_complete",
        "restored": restored is not None,
        "co_warning": co_warning,
        "recommended_safe_shelf_desc": safe_desc,
        "chemical": {
            "id": chem.id,
            "name": chem.name,
            "shelf_id": chem.shelf_id,
            "shelf_row": chem.shelf_row,
            "shelf_col": chem.shelf_col,
            "weight": chem.weight,
        },
    }


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

    # 무게 변화량으로 반입(증가)/반출(감소) 세션을 진행시킨다
    session_result = None
    delta = req.weight - prev_w
    if delta >= MIN_EVENT_DELTA_KG:
        session_result = _handle_checkin_increase(db, shelf, delta)
    elif delta <= -MIN_EVENT_DELTA_KG:
        session_result = checkout_flow.handle_weight_drop(db, shelf, -delta)

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
