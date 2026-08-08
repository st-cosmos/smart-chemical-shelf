import time

checkin_session = {
    "active": False,
    "chemical_name": "",
    "start_time": 0.0,
    "timeout_seconds": 15.0,
    "username": "",
    "expiration_date": None
}

# 반출 세션: 스캔 → 선반 무게 감소 감지로 확정 (checkout_flow.py 에서 관리)
checkout_session = {
    "active": False,
    "chemical_name": "",
    "chemical_id": "",      # 직접 선택으로 특정 병이 지정된 경우
    "start_time": 0.0,
    "timeout_seconds": 20.0,
    "username": "",
    "last_event": None,     # 진행 중 경고 (예: 다른 시약 회수 감지) — 앱이 1회 읽으면 소거
    "last_result": None,    # 확정 결과 — 다음 세션 시작 전까지 유지
}
