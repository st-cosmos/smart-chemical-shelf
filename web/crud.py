from sqlalchemy.orm import Session
import models
import schemas
from datetime import datetime, timedelta

def init_db_seed(db: Session):
    # Check if database is already seeded
    if db.query(models.User).first() is not None:
        return

    # 1. Seed Users
    # 웹 로그인 비밀번호는 qwer1234, 앱 로그인 PIN은 1234 로 통일한다.
    users = [
        models.User(username="kim.lab", password="qwer1234", pin="1234", nickname="김연구", role="관리자"),
        models.User(username="lee.exp", password="qwer1234", pin="1234", nickname="이실험", role="연구원"),
        models.User(username="park.safe", password="qwer1234", pin="1234", nickname="박안전", role="관리자"),
        models.User(username="jung.dev", password="qwer1234", pin="1234", nickname="정개발", role="연구원"),
        models.User(username="kang.design", password="qwer1234", pin="1234", nickname="강디자인", role="연구원"),
        models.User(username="yoon.plan", password="qwer1234", pin="1234", nickname="윤기획", role="연구원"),
        models.User(username="cho.test", password="qwer1234", pin="1234", nickname="조테스트", role="연구원"),
        models.User(username="shin.intern", password="qwer1234", pin="1234", nickname="신인턴", role="연구원"),
    ]
    for u in users:
        db.add(u)
    db.commit()

    # 2. Seed Shelves
    # We create shelves with both registered and unregistered status
    shelves = [
        # Shelf A (3 rows x 4 columns)
        models.Shelf(id="SHELF-01B4", name="수납칸 A1", parent_shelf="A", row=1, col=1, weight=4.2, prev_weight=4.2, battery=86, status="registered"),
        models.Shelf(id="SHELF-02C7", name="수납칸 A2", parent_shelf="A", row=1, col=2, weight=3.1, prev_weight=3.1, battery=64, status="registered"),
        models.Shelf(id="SHELF-05D1", name="수납칸 A3", parent_shelf="A", row=1, col=3, weight=5.1, prev_weight=5.1, battery=91, status="registered"),
        # Empty slots in Shelf A can be represented as either unregistered/unassigned
        models.Shelf(id="SHELF-07E2", name="수납칸 A5", parent_shelf="A", row=2, col=1, weight=2.8, prev_weight=2.8, battery=77, status="registered"),
        models.Shelf(id="SHELF-09A0", name="수납칸 A8", parent_shelf="A", row=2, col=4, weight=1.9, prev_weight=1.9, battery=58, status="registered"),
        models.Shelf(id="SHELF-0BF3", name="수납칸 A10", parent_shelf="A", row=3, col=2, weight=6.0, prev_weight=6.0, battery=45, status="registered"),
        
        # Shelf B (3 rows x 3 columns)
        models.Shelf(id="ESP-01", name="수납칸 B1", parent_shelf="B", row=1, col=1, weight=0.8, prev_weight=0.8, battery=95, status="registered"),
        models.Shelf(id="ESP-02", name="수납칸 B2", parent_shelf="B", row=1, col=2, weight=1.1, prev_weight=1.1, battery=90, status="registered"),
        models.Shelf(id="ESP-03", name="수납칸 B4", parent_shelf="B", row=2, col=1, weight=0.5, prev_weight=0.5, battery=88, status="registered"),
        models.Shelf(id="SHELF-B23", name="수납칸 B6", parent_shelf="B", row=2, col=3, weight=1.4, prev_weight=1.4, battery=82, status="registered"),
        
        # Unregistered devices
        models.Shelf(id="SHELF-A3F2", weight=0.0, prev_weight=0.0, battery=100, status="unregistered"),
        models.Shelf(id="SHELF-B77E", weight=0.0, prev_weight=0.0, battery=100, status="unregistered"),
    ]
    for s in shelves:
        db.add(s)
    db.commit()

    # 3. Seed Chemicals
    # We assign them to the seeded shelves
    chemicals = [
        models.Chemical(
            id="chem_1",
            name="에탄올 95%",
            cas_no="64-17-5",
            formula="EtOH",
            weight=1.2,
            shelf_id="SHELF-05D1",  # Let's say it's on Shelf A, 2-3 (Wait, A-2-3 shelf is registered under name 수납칸 A7 or dynamically updated, but we map it directly)
            shelf_row=2,
            shelf_col=3,
            current_status="비치중",
            time_in="2026-07-18 09:12:00",
            expiration_date="2027-02-11",
            manufacturer="덕산약품"
        ),
        models.Chemical(
            id="chem_2",
            name="황산 0.1M",
            cas_no="7664-93-9",
            formula="H₂SO₄",
            weight=0.8,
            shelf_id="ESP-01",
            shelf_row=1,
            shelf_col=2,
            current_status="반출중",
            holder_username="kim.lab",
            time_in="2026-07-18 09:12:00",
            time_out="2026-07-19 14:32:00",
            expiration_date="2027-02-11",
            manufacturer="삼전순약"
        ),
        models.Chemical(
            id="chem_3",
            name="아세톤",
            cas_no="67-64-1",
            formula="C₃H₆O",
            weight=0.9,
            shelf_id="SHELF-02C7",
            shelf_row=1,
            shelf_col=4,
            current_status="비치중",
            time_in="2026-07-17 11:00:00",
            expiration_date="2026-12-01",
            manufacturer="대정화금"
        ),
        models.Chemical(
            id="chem_4",
            name="시안화칼륨",
            cas_no="151-50-8",
            formula="KCN",
            weight=0.3,
            shelf_id="SHELF-01B4",
            shelf_row=1,
            shelf_col=3,
            current_status="비치중",
            time_in="2026-07-15 11:05:00",
            expiration_date="2028-01-15",
            manufacturer="삼전순약"
        ),
        models.Chemical(
            id="chem_5",
            name="질산은 표준액",
            cas_no="7761-88-8",
            formula="AgNO₃",
            weight=0.5,
            shelf_id="ESP-03",
            shelf_row=2,
            shelf_col=1,
            current_status="비치중",
            time_in="2026-07-10 10:00:00",
            expiration_date="2026-06-30", # EXPIRED!
            manufacturer="삼전순약"
        ),
        models.Chemical(
            id="chem_6",
            name="메탄올",
            cas_no="67-56-1",
            formula="CH₃OH",
            weight=1.1,
            shelf_id="ESP-02",
            shelf_row=1,
            shelf_col=2,
            current_status="반출중",
            holder_username="lee.exp",
            time_in="2026-07-19 09:12:00",
            time_out="2026-07-19 09:12:00",
            expiration_date="2026-08-02", # Expiring soon
            manufacturer="시그마알드리치"
        ),
        models.Chemical(
            id="chem_7",
            name="톨루엔",
            cas_no="108-88-3",
            formula="C₇H₈",
            weight=1.4,
            shelf_id="SHELF-B23",
            shelf_row=2,
            shelf_col=3,
            current_status="비치중",
            time_in="2026-07-15 11:05:00",
            expiration_date="2027-05-20",
            manufacturer="덕산약품"
        )
    ]
    for c in chemicals:
        db.add(c)
    db.commit()

    # Update shelf weights based on initial chemicals
    for c in chemicals:
        if c.current_status == "비치중" and c.shelf_id:
            shelf = db.query(models.Shelf).filter(models.Shelf.id == c.shelf_id).first()
            if shelf:
                shelf.weight = c.weight
                shelf.prev_weight = c.weight
    db.commit()

    # 4. Seed Logs
    logs = [
        models.Log(chemical_id="chem_2", chemical_name="황산 0.1M", action="반출", operator_name="김연구", details="보관 위치였던 선반 A · 1행 2열이 비워졌습니다", timestamp=datetime.now() - timedelta(minutes=10)),
        models.Log(chemical_id="chem_2", chemical_name="황산 0.1M", action="반입", operator_name="김연구", details="신규로 들어옴: 선반 A · 1행 2열에 등록됨", timestamp=datetime.now() - timedelta(hours=8)),
        models.Log(chemical_id="chem_6", chemical_name="메탄올", action="반출", operator_name="이실험", details="보관 위치였던 선반 B · 1행 2열이 비워졌습니다", timestamp=datetime.now() - timedelta(hours=24)),
        models.Log(chemical_id="chem_6", chemical_name="메탄올", action="반입", operator_name="이실험", details="재반입 완료", timestamp=datetime.now() - timedelta(days=4)),
    ]
    for l in logs:
        db.add(l)
    db.commit()

    # 5. Seed Orders
    orders = [
        models.Order(chemical_name="메탄올 1L", formula="CH₃OH", manufacturer="시그마알드리치", current_qty="15%", threshold_qty="30%", price=24000, selected=True),
        models.Order(chemical_name="아세톤 500mL", formula="C₃H₆O", manufacturer="대정화금", current_qty="18%", threshold_qty="40%", price=36000, selected=True),
        models.Order(chemical_name="질산은 표준액 0.1N", formula="AgNO₃", manufacturer="삼전순약", current_qty="12%", threshold_qty="25%", price=88000, selected=True),
        models.Order(chemical_name="에탄올 95% 4L", formula="EtOH", manufacturer="덕산약품", current_qty="22%", threshold_qty="35%", price=36000, selected=False),
    ]
    for o in orders:
        db.add(o)
    db.commit()

    # 6. Seed ShelfConfigs
    configs = [
        models.ShelfConfig(id="A", name="선반 A", rows=3, cols=4),
        models.ShelfConfig(id="B", name="선반 B", rows=3, cols=3),
    ]
    for cfg in configs:
        db.add(cfg)
    db.commit()

