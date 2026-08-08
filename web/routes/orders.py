from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from database import get_db
import models
import schemas

router = APIRouter(prefix="/api/orders", tags=["orders"])

@router.get("")
def get_orders(db: Session = Depends(get_db)):
    return db.query(models.Order).all()

@router.post("")
def create_order(req: schemas.OrderCreate, db: Session = Depends(get_db)):
    new_ord = models.Order(
        chemical_name=req.chemical_name,
        formula=req.formula,
        manufacturer=req.manufacturer,
        current_qty=req.current_qty,
        threshold_qty=req.threshold_qty,
        price=req.price,
        purchase_link=req.purchase_link
    )
    db.add(new_ord)
    db.commit()
    db.refresh(new_ord)
    return new_ord

@router.put("/{order_id}")
def update_order(order_id: int, req: schemas.OrderUpdate, db: Session = Depends(get_db)):
    ord_item = db.query(models.Order).filter(models.Order.id == order_id).first()
    if not ord_item:
        raise HTTPException(status_code=404, detail="주문 항목을 찾을 수 없습니다.")
        
    if req.selected is not None:
        ord_item.selected = req.selected
    if req.status:
        ord_item.status = req.status
    if req.purchase_link is not None:
        # 빈 문자열이 오면 링크 삭제로 처리한다
        ord_item.purchase_link = req.purchase_link.strip() or None

    db.commit()
    db.refresh(ord_item)
    return ord_item

@router.post("/confirm")
def confirm_orders(db: Session = Depends(get_db)):
    # Find all selected orders
    selected_items = db.query(models.Order).filter(models.Order.selected == True).all()
    if not selected_items:
        raise HTTPException(status_code=400, detail="선택된 주문 항목이 없습니다.")
        
    for item in selected_items:
        item.status = "ordered"
        item.selected = False # uncheck after ordering
        
    db.commit()
    return {"status": "success", "count": len(selected_items)}
