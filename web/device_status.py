"""선반 디바이스 하트비트 추적 (in-memory).

게이트웨이가 노드를 대신해 1초 주기로 LED 상태를 폴링하므로
(GET /api/led/{id}) 마지막 접속 시각으로 온라인 여부를 판단한다.
서버를 재시작하면 초기화되지만, 살아 있는 디바이스는 수 초 안에
다시 기록된다.

IDLE 전력 모드(앱 로그인 없음, docs/power-modes.md)에서는 게이트웨이의
대행 폴링이 5초로 느려지고 노드 접촉도 30초 하트비트뿐이라, 판정 창을
모드에 따라 넓힌다.
"""
import time

import shelf_power

# 이 시간 안에 접속 기록이 있어야 온라인으로 본다 (폴링 주기 1초 대비 여유)
ONLINE_WINDOW_SECONDS = 15.0
# IDLE 모드: 게이트웨이 대행 폴링 5초 + 노드 하트비트 30초 대비 여유
IDLE_ONLINE_WINDOW_SECONDS = 90.0

_last_seen = {}


def mark_seen(device_id: str):
    _last_seen[device_id] = time.time()


def is_online(device_id: str) -> bool:
    seen = _last_seen.get(device_id)
    if seen is None:
        return False
    window = (ONLINE_WINDOW_SECONDS if shelf_power.mode() == "active"
              else IDLE_ONLINE_WINDOW_SECONDS)
    return (time.time() - seen) <= window
