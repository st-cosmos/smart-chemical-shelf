from pydantic import BaseModel
from typing import Optional, List

class UserRegister(BaseModel):
    username: str
    password: str        # 웹 로그인용 (8자 이상, 숫자·특수문자 포함)
    nickname: str
    pin: str             # 앱 로그인용 4자리 PIN
    role: str

class UserLogin(BaseModel):
    # 웹 로그인: 긴 비밀번호로 인증
    username: str
    password: str

class UserPinLogin(BaseModel):
    # 안드로이드 앱 로그인: 4자리 PIN으로 인증
    username: str
    pin: str

class UserProfileUpdate(BaseModel):
    # 본인 프로필 수정: 별명은 필수, 비밀번호·PIN은 비우면 기존 값 유지
    nickname: str
    password: Optional[str] = None
    pin: Optional[str] = None

class UserResponse(BaseModel):
    username: str
    nickname: str
    role: str
    created_at: str

    class Config:
        from_attributes = True

class WeightUpdate(BaseModel):
    weight: float
    battery: Optional[int] = None

class LedUpdate(BaseModel):
    led_on: bool
    led_message: str = ""

class ScanInRequest(BaseModel):
    ocr_text: str
    username: str  # who scanned it

class ScanOutRequest(BaseModel):
    ocr_text: str
    username: str  # who scanned it

class SelectLedRequest(BaseModel):
    chem_id: str

class ShelfRegister(BaseModel):
    name: str               # e.g. 수납칸 A7
    parent_shelf: str       # e.g. A, B
    row: int
    col: int

class OrderCreate(BaseModel):
    chemical_name: str
    formula: Optional[str] = None
    manufacturer: Optional[str] = None
    current_qty: str
    threshold_qty: str
    price: int

class OrderUpdate(BaseModel):
    selected: bool
    status: Optional[str] = None

class ShelfResponse(BaseModel):
    id: str
    name: Optional[str] = None
    parent_shelf: Optional[str] = None
    row: Optional[int] = None
    col: Optional[int] = None
    weight: float
    prev_weight: float
    battery: int
    status: str
    led_on: bool
    led_message: str
    updated_time: str

    class Config:
        from_attributes = True

class ChemicalResponse(BaseModel):
    id: str
    name: str
    cas_no: Optional[str] = None
    formula: Optional[str] = None
    weight: float
    shelf_id: Optional[str] = None
    shelf_row: Optional[int] = None
    shelf_col: Optional[int] = None
    current_status: str
    holder_username: Optional[str] = None
    time_in: Optional[str] = None
    time_out: Optional[str] = None
    expiration_date: Optional[str] = None
    manufacturer: Optional[str] = None

    class Config:
        from_attributes = True

class LogResponse(BaseModel):
    id: int
    chemical_id: str
    chemical_name: str
    action: str
    operator_name: str
    details: Optional[str] = None
    timestamp: str

    class Config:
        from_attributes = True

# Legacy support
class LedCommand(BaseModel):
    on: bool
    by: str

class PotentiometerEvent(BaseModel):
    value: int
    esp_id: str = "ESP-01"
