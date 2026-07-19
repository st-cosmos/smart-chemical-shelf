from sqlalchemy import Column, Integer, String, Float, Boolean, DateTime, Date, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime
from database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True, nullable=False)
    password = Column(String, nullable=False)
    nickname = Column(String, nullable=False)
    role = Column(String, default="연구원")  # 관리자, 연구원, 보조원
    created_at = Column(DateTime, default=datetime.utcnow)

class Shelf(Base):
    __tablename__ = "shelves"

    id = Column(String, primary_key=True, index=True)  # Device ID, e.g., SHELF-A3F2, ESP-01
    name = Column(String, nullable=True)               # e.g., 수납칸 A1
    parent_shelf = Column(String, nullable=True)       # e.g., A, B
    row = Column(Integer, nullable=True)               # 1-indexed
    col = Column(Integer, nullable=True)               # 1-indexed
    weight = Column(Float, default=0.0)
    prev_weight = Column(Float, default=0.0)
    battery = Column(Integer, default=100)
    status = Column(String, default="unregistered")     # registered, unregistered
    led_on = Column(Boolean, default=False)
    led_message = Column(String, default="")
    updated_time = Column(String, default="-")

class Chemical(Base):
    __tablename__ = "chemicals"

    id = Column(String, primary_key=True, index=True)  # e.g., chem_1234
    name = Column(String, index=True, nullable=False)
    cas_no = Column(String, nullable=True)
    formula = Column(String, nullable=True)
    weight = Column(Float, default=0.0)
    shelf_id = Column(String, ForeignKey("shelves.id"), nullable=True)
    shelf_row = Column(Integer, nullable=True)
    shelf_col = Column(Integer, nullable=True)
    current_status = Column(String, default="비치중")   # 비치중, 반출중
    holder_username = Column(String, nullable=True)
    time_in = Column(String, nullable=True)
    time_out = Column(String, nullable=True)
    expiration_date = Column(String, nullable=True)     # YYYY-MM-DD
    manufacturer = Column(String, nullable=True)

class Log(Base):
    __tablename__ = "logs"

    id = Column(Integer, primary_key=True, index=True)
    chemical_id = Column(String, nullable=False)
    chemical_name = Column(String, nullable=False)
    action = Column(String, nullable=False)             # 반입, 반출
    operator_name = Column(String, nullable=False)      # Nickname or Username
    details = Column(String, nullable=True)
    timestamp = Column(DateTime, default=datetime.utcnow)

class Order(Base):
    __tablename__ = "orders"

    id = Column(Integer, primary_key=True, index=True)
    chemical_name = Column(String, nullable=False)
    formula = Column(String, nullable=True)
    manufacturer = Column(String, nullable=True)
    current_qty = Column(String, nullable=True)         # e.g., 15%
    threshold_qty = Column(String, nullable=True)       # e.g., 30%
    price = Column(Integer, default=0)                  # Price in KRW
    selected = Column(Boolean, default=False)
    status = Column(String, default="pending")          # pending, ordered

class ShelfConfig(Base):
    __tablename__ = "shelf_configs"

    id = Column(String, primary_key=True)               # e.g., A, B
    name = Column(String, nullable=False)               # e.g., 선반 A, 선반 B
    rows = Column(Integer, default=3)
    cols = Column(Integer, default=4)

