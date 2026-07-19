from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List
from database import get_db
import models
import schemas
from datetime import datetime

router = APIRouter(prefix="/api/users", tags=["users"])

@router.post("/register", response_model=schemas.UserResponse)
def register_user(req: schemas.UserRegister, db: Session = Depends(get_db)):
    db_user = db.query(models.User).filter(models.User.username == req.username).first()
    if db_user:
        raise HTTPException(status_code=400, detail="이미 존재하는 사용자 이름입니다.")
    
    new_user = models.User(
        username=req.username,
        password=req.password,
        nickname=req.nickname,
        role=req.role
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    
    return schemas.UserResponse(
        username=new_user.username,
        nickname=new_user.nickname,
        role=new_user.role,
        created_at=new_user.created_at.strftime("%Y-%m-%d %H:%M:%S")
    )

@router.get("", response_model=List[schemas.UserResponse])
def get_users(db: Session = Depends(get_db)):
    users = db.query(models.User).all()
    return [
        schemas.UserResponse(
            username=u.username,
            nickname=u.nickname,
            role=u.role,
            created_at=u.created_at.strftime("%Y-%m-%d %H:%M:%S")
        ) for u in users
    ]
