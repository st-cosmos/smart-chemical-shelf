"""ESP 디바이스 하트비트 추적 (in-memory).

디바이스는 1초마다 LED 상태를 폴링하므로(GET /api/led/{id})
마지막 접속 시각으로 온라인 여부를 판단한다. 서버를 재시작하면
초기화되지만, 살아 있는 디바이스는 수 초 안에 다시 기록된다.
"""
import time

# 이 시간 안에 접속 기록이 있어야 온라인으로 본다 (폴링 주기 1초 대비 여유)
ONLINE_WINDOW_SECONDS = 15.0

_last_seen = {}


def mark_seen(device_id: str):
    _last_seen[device_id] = time.time()


def is_online(device_id: str) -> bool:
    seen = _last_seen.get(device_id)
    return seen is not None and (time.time() - seen) <= ONLINE_WINDOW_SECONDS
