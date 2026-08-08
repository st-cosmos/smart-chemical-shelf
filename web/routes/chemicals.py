from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from database import get_db
import checkout_flow
import matching
import models
import schemas
from datetime import datetime
import time

router = APIRouter(prefix="/api/chemicals", tags=["chemicals"])


def _resolve_chemical_name(db: Session, req, error_detail: str) -> str:
    """scan-in/out 공통: 앱이 확정한 chemical_name 이 있으면 그대로,
    없으면(구버전 클라이언트) 서버 매칭을 시도하되 자동 확정 수준일 때만 인정한다."""
    if req.chemical_name:
        return req.chemical_name
    result = matching.match(db, req.ocr_text)
    if result["status"] == "matched":
        return result["chemical_name"]
    raise HTTPException(status_code=400, detail=error_detail)


@router.get("", response_model=List[schemas.ChemicalResponse])
def get_chemicals(db: Session = Depends(get_db)):
    return db.query(models.Chemical).all()

@router.get("/ocr-chemicals")
def get_ocr_chemicals(db: Session = Depends(get_db)):
    # (구버전 호환) 인식 대상 시약명 목록 — 이제 별칭 사전 기반
    return matching.known_names(db)

@router.get("/known-names")
def get_known_names(db: Session = Depends(get_db)):
    """앱의 '직접 선택' 폴백 UI 용 표준명 목록."""
    return matching.known_names(db)

@router.post("/match")
def match_chemical(req: schemas.MatchRequest, db: Session = Depends(get_db)):
    """스캔 중 인식 시도. 바코드 > CAS > 별칭 > 유사도 순으로 매칭하고
    확신이 없으면 needs_confirmation 으로 후보를 돌려준다."""
    return matching.match(db, req.ocr_text, req.barcode)

@router.post("/match/confirm")
def confirm_match(req: schemas.MatchConfirmRequest, db: Session = Depends(get_db)):
    """확정된 (바코드/토큰 → 시약) 매핑을 학습해 다음 스캔부터 즉시 인식되게 한다."""
    learned = matching.confirm(db, req.chemical_name, req.barcode, req.matched_token)
    return {"status": "success", "learned": learned}

@router.post("/scan-in")
def scan_in(req: schemas.ScanInRequest, db: Session = Depends(get_db)):
    matched_std_name = _resolve_chemical_name(
        db, req,
        f"인식된 텍스트 '{req.ocr_text}'에서 보관 가능한 시약을 매칭하지 못했습니다."
    )
        
    # Check if there is history of this chemical name to find designated shelf location
    prev_chem = db.query(models.Chemical).filter(
        models.Chemical.name == matched_std_name
    ).order_by(models.Chemical.time_in.desc()).first()
    
    prev_shelf_id = None
    prev_shelf_desc = ""
    
    if prev_chem and prev_chem.shelf_id:
        prev_shelf_id = prev_chem.shelf_id
        shelf = db.query(models.Shelf).filter(models.Shelf.id == prev_shelf_id).first()
        if shelf:
            prev_shelf_desc = f"선반 {shelf.parent_shelf} · {shelf.row}행 {shelf.col}열"
            # Turn on LED for this shelf
            shelf.led_on = True
            shelf.led_message = f"기존 반입 이력 위치: {matched_std_name}"
            shelf.updated_time = datetime.now().strftime("%H:%M:%S")
            db.commit()

    # Start active check-in session
    from session_store import checkin_session
    checkin_session["active"] = True
    checkin_session["chemical_name"] = matched_std_name
    checkin_session["start_time"] = time.time()
    checkin_session["username"] = req.username
    
    return {
        "status": "success",
        "chemical_name": matched_std_name,
        "has_history": prev_chem is not None,
        "recommended_shelf": prev_shelf_id,
        "recommended_shelf_desc": prev_shelf_desc,
        "timeout_seconds": checkin_session["timeout_seconds"]
    }

@router.post("/scan-out")
def scan_out(req: schemas.ScanOutRequest, db: Session = Depends(get_db)):
    """반출 스캔 → 즉시 확정하지 않고 반출 세션을 시작한다.

    후보 병(들)의 선반 LED 를 켜 위치를 안내하고, 실제 무게 감소가 감지된
    칸으로 어느 병인지 확정한다 (동일 이름 병 개체 식별 + 감소량 검증).
    확정/타임아웃 여부는 GET /api/checkout-session 폴링으로 전달된다.
    """
    matched_std_name = _resolve_chemical_name(
        db, req,
        f"인식된 텍스트 '{req.ocr_text}'에서 반출할 시약을 식별할 수 없습니다."
    )

    query = db.query(models.Chemical).filter(
        models.Chemical.name == matched_std_name,
        models.Chemical.current_status == "비치중",
    )
    if req.chemical_id:
        query = query.filter(models.Chemical.id == req.chemical_id)
    candidate_chems = query.all()
    if not candidate_chems:
        raise HTTPException(
            status_code=404,
            detail=f"비치 중인 시약 목록에서 '{matched_std_name}'을(를) 찾을 수 없습니다."
        )

    # 선반 무게가 이미 비어 있는 병 = 회수가 이미 일어난 사후 스캔
    # ('반출 스캔 미완료' 알림 흐름) → 무게 감소를 기다리지 않고 즉시 확정
    for chem in candidate_chems:
        if not chem.shelf_id:
            continue
        shelf = db.query(models.Shelf).filter(models.Shelf.id == chem.shelf_id).first()
        if shelf and shelf.weight < checkout_flow.EMPTY_SHELF_KG:
            result = checkout_flow.finalize_already_removed(db, chem, req.username)
            db.refresh(chem)
            return {
                "status": "success",
                "already_removed": True,
                "chemical": chem,
                "weight_verified": result["weight_verified"],
                "measured_delta": result["measured_delta"],
            }

    candidates = checkout_flow.start(
        db, matched_std_name, req.username, chemical_id=req.chemical_id
    )
    from session_store import checkout_session
    return {
        "status": "pending",
        "chemical_name": matched_std_name,
        "candidates": candidates,
        "timeout_seconds": checkout_session["timeout_seconds"],
    }

@router.post("/scan-out/force")
def scan_out_force(req: schemas.ScanOutForceRequest, db: Session = Depends(get_db)):
    """무게 감소가 감지되지 않았을 때(타임아웃) 사용자가 확인 없이 기록하는 경로."""
    query = db.query(models.Chemical).filter(models.Chemical.current_status == "비치중")
    if req.chemical_id:
        query = query.filter(models.Chemical.id == req.chemical_id)
    elif req.chemical_name:
        query = query.filter(models.Chemical.name == req.chemical_name)
    else:
        raise HTTPException(status_code=400, detail="chemical_id 또는 chemical_name 이 필요합니다.")

    chem = query.first()
    if not chem:
        raise HTTPException(status_code=404, detail="비치 중인 해당 시약을 찾을 수 없습니다.")

    checkout_flow.force_finalize(db, chem, req.username)
    db.refresh(chem)
    return {"status": "success", "chemical": chem}

@router.post("/select-led")
def select_led(req: schemas.SelectLedRequest, db: Session = Depends(get_db)):
    chem = db.query(models.Chemical).filter(models.Chemical.id == req.chem_id).first()
    if not chem:
        raise HTTPException(status_code=404, detail="시약을 찾을 수 없습니다.")
        
    if chem.current_status != "비치중":
        raise HTTPException(status_code=400, detail="반입 상태인 시약에 대해서만 LED를 켤 수 있습니다.")
        
    shelf = db.query(models.Shelf).filter(models.Shelf.id == chem.shelf_id).first()
    if not shelf:
        raise HTTPException(status_code=404, detail="보관된 선반 모듈을 찾을 수 없습니다.")
        
    shelf.led_on = True
    shelf.led_message = f"찾는 시약 위치: {chem.name}"
    shelf.updated_time = datetime.now().strftime("%H:%M:%S")
    
    db.commit()
    return {
        "status": "success",
        "shelf_id": shelf.id,
        "chemical_name": chem.name,
        "parent_shelf": shelf.parent_shelf,
        "row": shelf.row,
        "col": shelf.col
    }

@router.get("/alerts")
def get_alerts(db: Session = Depends(get_db)):
    # 1. Un-scanned checkouts:
    # A chemical with status "비치중" but its shelf weight is near 0
    unscanned_checkouts = []
    chemicals = db.query(models.Chemical).filter(models.Chemical.current_status == "비치중").all()
    for c in chemicals:
        if c.shelf_id:
            shelf = db.query(models.Shelf).filter(models.Shelf.id == c.shelf_id).first()
            if shelf and shelf.weight < 0.1 and c.weight >= 0.2:
                unscanned_checkouts.append({
                    "chemical_id": c.id,
                    "chemical_name": c.name,
                    "shelf_id": c.shelf_id,
                    "shelf_desc": f"선반 {shelf.parent_shelf} · {shelf.row}행 {shelf.col}열"
                })
                
    # 2. Expired chemicals:
    expired_chemicals = []
    current_date = datetime.now().date()
    for c in chemicals:
        if c.expiration_date:
            try:
                exp_date = datetime.strptime(c.expiration_date, "%Y-%m-%d").date()
                if exp_date < current_date:
                    days_over = (current_date - exp_date).days
                    expired_chemicals.append({
                        "chemical_id": c.id,
                        "chemical_name": c.name,
                        "expiration_date": c.expiration_date,
                        "days_over": days_over
                    })
                elif (exp_date - current_date).days <= 30: # Expiring within 30 days
                    expired_chemicals.append({
                        "chemical_id": c.id,
                        "chemical_name": c.name,
                        "expiration_date": c.expiration_date,
                        "days_over": 0,
                        "warning": "임박"
                    })
            except ValueError:
                pass
                
    # 3. Co-storage safety warning (LLM / Rule-based dynamic incompatibility check):
    co_storage_warnings = []
    import json
    import services.llm_safety as llm_safety

    # Ensure all checked-in chemicals have incompatible_chemicals analyzed
    for c in chemicals:
        llm_safety.ensure_chemical_incompatibility_info(c, db)

    stored_chems = [c for c in chemicals if c.shelf_id]
    n = len(stored_chems)

    for i in range(n):
        for j in range(i + 1, n):
            c1 = stored_chems[i]
            c2 = stored_chems[j]

            shelf1 = db.query(models.Shelf).filter(models.Shelf.id == c1.shelf_id).first()
            shelf2 = db.query(models.Shelf).filter(models.Shelf.id == c2.shelf_id).first()

            if not shelf1 or not shelf2:
                continue

            # Check if stored on adjacent positions (same parent shelf & row/col distance <= 1)
            is_adjacent = False
            if shelf1.parent_shelf and shelf2.parent_shelf and shelf1.parent_shelf == shelf2.parent_shelf:
                r1, c1_col = shelf1.row or 1, shelf1.col or 1
                r2, c2_col = shelf2.row or 1, shelf2.col or 1
                if abs(r1 - r2) <= 1 and abs(c1_col - c2_col) <= 1:
                    is_adjacent = True
            elif shelf1.id == shelf2.id:
                is_adjacent = True

            if not is_adjacent:
                continue

            # Check incompatibility matching between c1 and c2
            incomp1 = json.loads(c1.incompatible_chemicals) if c1.incompatible_chemicals else []
            incomp2 = json.loads(c2.incompatible_chemicals) if c2.incompatible_chemicals else []

            is_incompatible = False
            if any(item in c2.name or c2.name in item for item in incomp1):
                is_incompatible = True
            elif any(item in c1.name or c1.name in item for item in incomp2):
                is_incompatible = True

            if is_incompatible:
                s1_desc = f"{shelf1.parent_shelf}·{shelf1.row}행{shelf1.col}열"
                s2_desc = f"{shelf2.parent_shelf}·{shelf2.row}행{shelf2.col}열"
                co_storage_warnings.append({
                    "chemical_1_id": c1.id,
                    "chemical_1_name": c1.name,
                    "chemical_2_id": c2.id,
                    "chemical_2_name": c2.name,
                    "shelf_desc": f"선반 {s1_desc} 및 {s2_desc}",
                    "message": f"🚨 [혼재 위험] {c1.name}와(과) {c2.name}은(는) 인접 보관 금지 시약입니다.",
                    "reason": c1.incompatible_reason or c2.incompatible_reason or "인접 보관 시 격렬한 반응, 유독가스 또는 화재/폭발 위험"
                })

    return {
        "unscanned_checkouts": unscanned_checkouts,
        "expired_chemicals": expired_chemicals,
        "co_storage_warnings": co_storage_warnings
    }
