#!/usr/bin/env python3
"""스마트 시약장 Thread 게이트웨이 — CoAP(메시) ↔ FastAPI 웹 서버(LAN) 브리지.

라즈베리파이에서 ot-daemon(wpan0) 옆에 상주하면서:

  * 노드의 무게 보고 (CoAP NON POST shelf/weight, JSON) 를 받아
    웹 서버의 `POST /api/weight/{device_id}` 로 전달한다 (mg -> g 환산,
    배터리 mV -> % 환산 포함).
  * 노드의 LED 폴링 (CoAP CON GET shelf/led?id=...) 에 캐시된 LED 상태로
    즉시 응답한다. 캐시는 백그라운드에서 `GET /api/led/{device_id}` 를
    1초 주기로 읽어 갱신하며, 이 폴링이 웹 서버 쪽 하트비트(mark_seen)
    역할도 겸한다 — 기존 ESP8266 이 1초 폴링하던 것과 같은 효과.
  * ff03::1 (realm-local all-nodes) 멀티캐스트에 가입해 노드의 게이트웨이
    탐색 요청을 받는다. 노드는 LED 폴에 응답한 유니캐스트 주소를 기억한다.

노드가 400 ms 안에 LED 응답을 받아야 하므로, CoAP 응답 경로에서는 절대
HTTP 를 기다리지 않는다 (항상 캐시 응답 + 비동기 갱신).

노드 쪽 프로토콜 규격: ../firmware/docs/build-and-flash.md §7
설정: gateway.toml (gateway.example.toml 참고)
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import signal
import socket
import sys
import time
import tomllib
from pathlib import Path

import shelfcoap

log = logging.getLogger("gateway")

DEFAULT_CONFIG: dict = {
    "server": {
        "base_url": "http://127.0.0.1:8000",
        "timeout_s": 3.0,
    },
    "coap": {
        "bind": "::",
        "port": shelfcoap.COAP_PORT,
        "multicast_group": "ff03::1",
        "interface": "wpan0",
        "rejoin_interval_s": 10.0,
        "base_path": "shelf",  # 노드 CONFIG_SHELF_COAP_BASE_PATH 와 일치해야 함
    },
    "led": {
        "poll_interval_s": 1.0,
        # 이 시간 동안 CoAP 접촉이 없는 노드는 서버 폴링을 중단한다.
        # 서버의 온라인 판정 창(15초)과 맞춰 두면 죽은 노드가 웹에서
        # 온라인으로 남는 시간이 최소화된다.
        "node_ttl_s": 15.0,
    },
    "status": {
        "bind": "0.0.0.0",
        "port": 8080,  # 0 = 상태 조회 HTTP 비활성
    },
    "nodes": {},  # 하드웨어 ID(16 hex) -> 웹 서버 선반 ID 매핑
}


def load_config(path: Path) -> dict:
    cfg = {k: (dict(v) if isinstance(v, dict) else v)
           for k, v in DEFAULT_CONFIG.items()}
    with open(path, "rb") as f:
        user = tomllib.load(f)
    for key, value in user.items():
        if isinstance(value, dict) and isinstance(cfg.get(key), dict):
            cfg[key].update(value)
        else:
            cfg[key] = value
    return cfg


def battery_percent(mv: int | None) -> int | None:
    """노드가 보낸 VDDH 전압(mV)을 웹 서버의 battery(%) 로 환산한다.

    펌웨어의 `shelf battery` 명령과 같은 기준:
      * 음수/None = 측정 실패 -> None (서버 값 유지)
      * 4400 mV 초과 = USB 전원, Q5 가 배터리를 분리한 상태라 배터리 전압이
        아니다 -> None (서버 값 유지)
      * 그 외 = 1S Li-ion 선형 추정 (3.3 V 빈 것 .. 4.2 V 가득)
    """
    if mv is None or mv < 0:
        return None
    if mv > 4400:
        return None
    pct = round((mv - 3300) * 100 / (4200 - 3300))
    return max(0, min(100, pct))


def parse_report(payload: bytes) -> dict:
    """노드의 무게 보고 JSON 을 검증해 정규화한다. 형식 오류는 ValueError.

    예: {"id":"1e0582a6f932714e","seq":12,"raw":1140384,"mg":11403840,"mv":4855}
    """
    try:
        data = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ValueError(f"invalid JSON: {e}") from None
    if not isinstance(data, dict):
        raise ValueError("payload is not a JSON object")

    hwid = data.get("id")
    if not isinstance(hwid, str) or not hwid:
        raise ValueError("missing node id")
    mg = data.get("mg")
    if not isinstance(mg, (int, float)) or isinstance(mg, bool):
        raise ValueError("missing weight (mg)")

    def _int_or_none(key):
        v = data.get(key)
        return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None

    return {
        "id": hwid,
        "mg": int(mg),
        "seq": _int_or_none("seq"),
        "raw": _int_or_none("raw"),
        "mv": _int_or_none("mv"),
    }


class Node:
    """게이트웨이가 기억하는 노드 하나의 상태."""

    def __init__(self, hwid: str, device_id: str):
        self.hwid = hwid
        self.device_id = device_id
        self.addr: tuple | None = None
        self.first_seen = time.time()
        self.last_seen = time.time()
        self.last_report: float | None = None
        self.seq: int | None = None
        self.raw: int | None = None
        self.mg: int | None = None
        self.grams: int | None = None
        self.mv: int | None = None
        self.battery: int | None = None
        self.led_on = False
        self.led_known = False   # 서버에서 LED 값을 한 번이라도 읽었는가
        self.http_ok: bool | None = None  # 서버 통신 상태 (전이 시에만 로그)
        self.reports = 0
        self.polls = 0

    def status(self) -> dict:
        now = time.time()
        return {
            "hwid": self.hwid,
            "device_id": self.device_id,
            "addr": self.addr[0] if self.addr else None,
            "grams": self.grams,
            "raw": self.raw,
            "mv": self.mv,
            "battery": self.battery,
            "seq": self.seq,
            "led_on": self.led_on,
            "reports": self.reports,
            "polls": self.polls,
            "last_seen_age_s": round(now - self.last_seen, 1),
            "last_report_age_s":
                round(now - self.last_report, 1) if self.last_report else None,
            "server_ok": self.http_ok,
        }


class Gateway:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.base_url: str = cfg["server"]["base_url"].rstrip("/")
        self.base_path: str = cfg["coap"]["base_path"]
        self.id_map: dict[str, str] = dict(cfg.get("nodes") or {})
        self.nodes: dict[str, Node] = {}
        self.http = None  # aiohttp.ClientSession, run() 에서 생성
        self.started = time.time()
        self._bg: set[asyncio.Task] = set()

    # ------------------------------------------------------------- CoAP 쪽

    async def coap_handler(self, msg: shelfcoap.Message, addr):
        path = msg.uri_path()

        if path == [self.base_path, "weight"]:
            if msg.code != shelfcoap.POST:
                return shelfcoap.METHOD_NOT_ALLOWED, b""
            try:
                report = parse_report(msg.payload)
            except ValueError as e:
                log.warning("잘못된 무게 보고 (%s from %s): %s",
                            e, addr[0], msg.payload[:64])
                return shelfcoap.BAD_REQUEST, b""
            self._handle_report(report, addr)
            return shelfcoap.CHANGED, b""

        if path == [self.base_path, "led"]:
            if msg.code != shelfcoap.GET:
                return shelfcoap.METHOD_NOT_ALLOWED, b""
            hwid = msg.uri_queries().get("id", "")
            if not hwid:
                return shelfcoap.BAD_REQUEST, b""
            node = self._node_for(hwid, addr)
            node.polls += 1
            # 절대 여기서 HTTP 를 기다리지 않는다 — 노드 타임아웃이 400 ms 다.
            return shelfcoap.CONTENT, (b"1" if node.led_on else b"0")

        return shelfcoap.NOT_FOUND, b""

    def _node_for(self, hwid: str, addr) -> Node:
        node = self.nodes.get(hwid)
        if node is None:
            node = Node(hwid, self.id_map.get(hwid, hwid))
            self.nodes[hwid] = node
            log.info("새 노드 %s -> 선반 ID '%s' (%s)",
                     hwid, node.device_id, addr[0])
            # 첫 폴에는 기본값(꺼짐)으로 답하고, 진짜 값을 바로 당겨온다.
            self._spawn(self._refresh_led(node))
        node.last_seen = time.time()
        node.addr = addr
        return node

    def _handle_report(self, report: dict, addr):
        node = self._node_for(report["id"], addr)
        node.reports += 1
        node.last_report = time.time()
        node.seq = report["seq"]
        node.raw = report["raw"]
        node.mg = report["mg"]
        node.mv = report["mv"]
        node.grams = round(report["mg"] / 1000)

        battery = battery_percent(node.mv)
        if battery is not None:
            node.battery = battery

        log.info("무게 보고 %s('%s') seq=%s raw=%s -> %s g, %s mV%s",
                 node.hwid, node.device_id, node.seq, node.raw, node.grams,
                 node.mv, f" (배터리 ~{battery}%)" if battery is not None else "")

        self._spawn(self._post_weight(node, node.grams, battery))

    # ------------------------------------------------------- 웹 서버 쪽

    async def _post_weight(self, node: Node, grams: int, battery: int | None):
        url = f"{self.base_url}/api/weight/{node.device_id}"
        body: dict = {"value": grams}
        if battery is not None:
            body["battery"] = battery
        try:
            async with self.http.post(url, json=body) as rsp:
                if rsp.status == 200:
                    self._server_state(node, True)
                else:
                    self._server_state(
                        node, False, f"POST {url} -> HTTP {rsp.status}")
        except Exception as e:  # aiohttp.ClientError, asyncio.TimeoutError, OSError...
            self._server_state(node, False, f"POST {url} 실패: {e!r}")

    async def _refresh_led(self, node: Node):
        """서버의 LED 상태를 캐시로 당겨온다. 서버 쪽 하트비트(mark_seen)도 겸함."""
        url = f"{self.base_url}/api/led/{node.device_id}"
        try:
            async with self.http.get(url) as rsp:
                if rsp.status != 200:
                    self._server_state(
                        node, False, f"GET {url} -> HTTP {rsp.status}")
                    return
                data = await rsp.json()
        except Exception as e:  # aiohttp.ClientError, asyncio.TimeoutError, OSError...
            self._server_state(node, False, f"GET {url} 실패: {e!r}")
            return

        self._server_state(node, True)
        want = bool(data.get("on"))
        if not node.led_known or want != node.led_on:
            log.info("LED %s('%s') -> %s",
                     node.hwid, node.device_id, "켜짐" if want else "꺼짐")
        node.led_on = want
        node.led_known = True

    def _server_state(self, node: Node, ok: bool, detail: str = ""):
        """서버 통신 성공/실패를 전이 시에만 로그로 남긴다 (스팸 방지)."""
        if ok and node.http_ok is not True:
            if node.http_ok is False:
                log.info("웹 서버 통신 복구 ('%s')", node.device_id)
            node.http_ok = True
        elif not ok and node.http_ok is not False:
            log.warning("웹 서버 통신 실패 ('%s'): %s", node.device_id, detail)
            node.http_ok = False

    async def _led_poll_loop(self):
        interval = float(self.cfg["led"]["poll_interval_s"])
        ttl = float(self.cfg["led"]["node_ttl_s"])
        while True:
            now = time.time()
            active = [n for n in self.nodes.values()
                      if now - n.last_seen <= ttl]
            if active:
                # _refresh_led 는 자체적으로 예외를 삼키지만, 어떤 예외도
                # 이 루프를 죽여 LED 캐시를 영영 묵히게 둬서는 안 된다.
                await asyncio.gather(*(self._refresh_led(n) for n in active),
                                     return_exceptions=True)
            await asyncio.sleep(interval)

    # ----------------------------------------------------- 멀티캐스트 유지

    async def _multicast_loop(self, sock: socket.socket):
        """ff03::1 가입을 유지한다. wpan0 은 ot-daemon 재시작 때 새로 만들어져
        기존 가입이 사라지므로 주기적으로 재가입해야 한다."""
        group = self.cfg["coap"]["multicast_group"]
        ifname = self.cfg["coap"]["interface"]
        interval = float(self.cfg["coap"]["rejoin_interval_s"])
        was_ok: bool | None = None
        while True:
            try:
                fresh = shelfcoap.join_multicast(sock, group, ifname)
                if fresh or was_ok is not True:
                    log.info("멀티캐스트 %s @ %s 가입", group, ifname)
                was_ok = True
            except OSError as e:
                if was_ok is not False:
                    log.warning("멀티캐스트 가입 실패 (%s @ %s): %s — "
                                "ot-daemon/wpan0 대기, %ss 마다 재시도",
                                group, ifname, e, interval)
                was_ok = False
            await asyncio.sleep(interval)

    # ------------------------------------------------------------ 상태 조회

    def status(self) -> dict:
        return {
            "uptime_s": round(time.time() - self.started, 1),
            "server": self.base_url,
            "interface": self.cfg["coap"]["interface"],
            "nodes": [n.status() for n in self.nodes.values()],
        }

    async def _start_status_server(self):
        port = int(self.cfg["status"]["port"])
        if port <= 0:
            return None
        from aiohttp import web

        async def handle(_request):
            return web.json_response(self.status())

        app = web.Application()
        app.add_routes([web.get("/", handle), web.get("/status", handle)])
        runner = web.AppRunner(app, access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, self.cfg["status"]["bind"], port)
        await site.start()
        log.info("상태 조회 HTTP: http://%s:%d/status",
                 self.cfg["status"]["bind"], port)
        return runner

    # ---------------------------------------------------------------- 실행

    def _spawn(self, coro):
        task = asyncio.get_running_loop().create_task(coro)
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    async def run(self):
        import aiohttp

        timeout = aiohttp.ClientTimeout(total=float(self.cfg["server"]["timeout_s"]))
        self.http = aiohttp.ClientSession(timeout=timeout)

        loop = asyncio.get_running_loop()
        transport, _protocol = await loop.create_datagram_endpoint(
            lambda: shelfcoap.CoapServer(self.coap_handler),
            local_addr=(self.cfg["coap"]["bind"], int(self.cfg["coap"]["port"])),
            family=socket.AF_INET6,
        )
        sock = transport.get_extra_info("socket")
        log.info("CoAP 서버 시작: [%s]:%s, 웹 서버: %s",
                 self.cfg["coap"]["bind"], self.cfg["coap"]["port"], self.base_url)
        if self.id_map:
            for hwid, device_id in self.id_map.items():
                log.info("노드 매핑: %s -> '%s'", hwid, device_id)

        status_runner = await self._start_status_server()

        stop = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM):
            with contextlib.suppress(NotImplementedError):  # Windows
                loop.add_signal_handler(sig, stop.set)

        workers = [
            loop.create_task(self._led_poll_loop()),
            loop.create_task(self._multicast_loop(sock)),
        ]
        try:
            await stop.wait()
            log.info("종료 중...")
        finally:
            for w in workers:
                w.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
            transport.close()
            if status_runner is not None:
                await status_runner.cleanup()
            await self.http.close()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="스마트 시약장 Thread 게이트웨이 (CoAP <-> 웹 서버 브리지)")
    parser.add_argument("-c", "--config", default=None,
                        help="설정 파일 (기본: 스크립트 옆의 gateway.toml)")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="디버그 로그")
    args = parser.parse_args(argv)

    # Windows 의 기본 Proactor 루프는 (일부 파이썬 빌드에서) UDP 수신이 동작하지
    # 않는다 — 개발 PC 에서 시험 구동할 때를 위한 처리. 파이(Linux)에서는 무관.
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname).1s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    if args.config:
        cfg_path = Path(args.config)
    else:
        cfg_path = Path(__file__).resolve().parent / "gateway.toml"
    if not cfg_path.exists():
        log.error("설정 파일이 없습니다: %s\n"
                  "gateway.example.toml 을 gateway.toml 로 복사한 뒤 "
                  "웹 서버 주소를 채워 넣으세요.", cfg_path)
        return 2

    try:
        cfg = load_config(cfg_path)
    except (OSError, tomllib.TOMLDecodeError) as e:
        log.error("설정 파일을 읽을 수 없습니다 (%s): %s", cfg_path, e)
        return 2

    gw = Gateway(cfg)
    try:
        asyncio.run(gw.run())
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
