from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from database import get_db
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
    matched_std_name = _resolve_chemical_name(
        db, req,
        f"인식된 텍스트 '{req.ocr_text}'에서 반출할 시약을 식별할 수 없습니다."
    )

    # Find active chemical in DB
    chem = db.query(models.Chemical).filter(
        models.Chemical.name == matched_std_name,
        models.Chemical.current_status == "비치중"
    ).first()
    
    if not chem:
        raise HTTPException(
            status_code=404,
            detail=f"비치 중인 시약 목록에서 '{matched_std_name}'을(를) 찾을 수 없습니다."
        )
        
    user = db.query(models.User).filter(models.User.username == req.username).first()
    operator_name = user.nickname if user else req.username
    
    # Mark as checked-out
    chem.current_status = "반출중"
    chem.holder_username = req.username
    chem.time_out = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    # Empty shelf weight
    shelf = None
    if chem.shelf_id:
        shelf = db.query(models.Shelf).filter(models.Shelf.id == chem.shelf_id).first()
        if shelf:
            shelf.prev_weight = shelf.weight
            shelf.weight = 0.0
            shelf.led_on = False
            shelf.led_message = ""
            shelf.updated_time = datetime.now().strftime("%H:%M:%S")
            
    # Record Log
    log = models.Log(
        chemical_id=chem.id,
        chemical_name=chem.name,
        action="반출",
        operator_name=operator_name,
        details=f"반출 기록 완료: 보관 위치였던 선반 {shelf.parent_shelf if shelf else ''} · {shelf.row if shelf else ''}행 {shelf.col if shelf else ''}열이 비워졌습니다"
    )
    db.add(log)
    
    db.commit()
    db.refresh(chem)
    return {
        "status": "success",
        "chemical": chem
    }

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
                
    # 3. Co-storage safety warning:
    # Check if sulfuric acid (황산) and potassium cyanide (시안화칼륨) are stored adjacent
    co_storage_warnings = []
    cyanides = [c for c in chemicals if "시안화칼륨" in c.name]
    acids = [c for c in chemicals if "황산" in c.name or "염산" in c.name or "질산" in c.name]
    
    for cy in cyanides:
        for ac in acids:
            if cy.shelf_id and ac.shelf_id:
                # Find shelves
                shelf_cy = db.query(models.Shelf).filter(models.Shelf.id == cy.shelf_id).first()
                shelf_ac = db.query(models.Shelf).filter(models.Shelf.id == ac.shelf_id).first()
                
                if shelf_cy and shelf_ac and shelf_cy.parent_shelf == shelf_ac.parent_shelf:
                    # Check row/col distance
                    row_diff = abs(shelf_cy.row - shelf_ac.row)
                    col_diff = abs(shelf_cy.col - shelf_ac.col)
                    if row_diff <= 1 and col_diff <= 1:
                        # Adjacent!
                        co_storage_warnings.append({
                            "chemical_1_id": cy.id,
                            "chemical_1_name": cy.name,
                            "chemical_2_id": ac.id,
                            "chemical_2_name": ac.name,
                            "shelf_desc": f"선반 {shelf_cy.parent_shelf} · 수납칸 {shelf_cy.row}행 {shelf_cy.col}열과 {shelf_ac.row}행 {shelf_ac.col}열",
                            "message": f"{ac.name}은(는) 인접 수납칸의 {cy.name}과(와) 반응 위험이 있어 동시 보관에 주의해야 합니다."
                        })
                        
    return {
        "unscanned_checkouts": unscanned_checkouts,
        "expired_chemicals": expired_chemicals,
        "co_storage_warnings": co_storage_warnings
    }
