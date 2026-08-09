# gateway — Thread 게이트웨이 (Raspberry Pi + nRF52840 동글)

선반 노드([`../firmware`](../firmware))가 붙을 Thread 네트워크를 만들고,
노드의 CoAP 트래픽을 기존 웹 서버([`../web`](../web))의 HTTP API 로 중개합니다.

```
[선반 노드 xN] --802.15.4/Thread--> [nRF52840 동글: ot-rcp]
                                        | USB CDC (/dev/ttyACM0)
                                   [ot-daemon] --> wpan0 (IPv6)
                                        |
                                   [gateway.py]  CoAP 서버 (shelfcoap.py)
                                        |          POST shelf/weight ← 노드
                                        |          GET  shelf/led    ← 노드 (700 ms)
                                        | HTTP
                                   [web 서버 FastAPI :8000]
                                        POST /api/weight/{id}  (무게 g + 배터리 %)
                                        GET  /api/led/{id}     (LED 캐시 갱신 = 하트비트)
```

검증 환경: Raspberry Pi (Debian 13 trixie, aarch64, Python 3.13), `192.168.35.187`
— §1~3 (동글 RCP, ot-daemon, Thread 네트워크, 노드 2대 접속)은 2026-08-08 실측 검증 완료.

## 구성

```
gateway.py             메인 프로그램: CoAP 서버 + 웹 서버 브리지 + 상태 조회 HTTP
shelfcoap.py           최소 CoAP 코덱/서버 (의존성 없음, ff03::1 멀티캐스트 가입 포함)
gateway.example.toml   설정 템플릿 (gateway.toml 로 복사해서 사용)
pyproject.toml         의존성 (aiohttp 하나)
systemd/               ot-daemon.service, shelf-gateway.service
test_*.py              단위 테스트: python -m unittest (의존성 불필요)
```

---

## 1. 동글에 RCP 펌웨어 굽기

동글은 Thread 라디오 역할만 합니다. 프로토콜 스택은 파이의 ot-daemon 이 돕니다.
빌드는 NCS 가 설치된 PC(Windows)에서 합니다.

### 1.1 빌드

NCS 에 샘플이 들어 있어서 소스를 따로 받을 필요가 없습니다.

```powershell
west build -b nrf52840dongle/nrf52840/bare -d C:\rcp -p always `
  C:/ncs/v3.4.0/nrf/samples/openthread/coprocessor -- `
  "-Dcoprocessor_EXTRA_DTC_OVERLAY_FILE=C:/ncs/v3.4.0/nrf/samples/openthread/coprocessor/boards/nrf52840dongle_nrf52840.overlay" `
  "-Dcoprocessor_EXTRA_CONF_FILE=C:/ncs/v3.4.0/nrf/samples/openthread/coprocessor/boards/nrf52840dongle_nrf52840.conf"
```

**보드 타깃은 `nrf52840dongle/nrf52840/bare` 입니다.** 일반 `nrf52840dongle/nrf52840` 은 Nordic MBR 뒤(0x1000)에 링크되는데, 부트로더가 없는 동글에 그대로 구우면 리셋 벡터가 비어 부팅하지 않습니다. `bare` 변종은 0x0 에 링크되어 단독으로 부팅합니다.

`bare` 변종에는 샘플의 `boards/nrf52840dongle_nrf52840.{overlay,conf}` 가 이름 규칙상 자동으로 붙지 않으므로 위처럼 명시해야 합니다. 빼먹으면 `zephyr,ot-uart` chosen 이 없어 빌드가 깨지고, USB VID 도 Zephyr 기본값으로 떨어집니다.

결과 확인:

| 항목 | 값 |
|---|---|
| `rom_start` | `0x00000000` |
| `CONFIG_CDC_ACM_SERIAL_VID` / `PID` | `0x1915` / `0x0000` |
| 제품 문자열 | `Thread Co-Processor` |

### 1.2 SWD 로 굽기

```powershell
$env:NRFUTIL_HOME = "C:\ncs\toolchains\dcbdc366a1\nrfutil\home"
Set-Alias nrfutil "C:\ncs\toolchains\dcbdc366a1\nrfutil\bin\nrfutil.exe"
$opt = "chip_erase_mode=ERASE_RANGES_TOUCHED_BY_FIRMWARE,verify=VERIFY_READ,reset=RESET_SYSTEM"
nrfutil device program --firmware C:\rcp\coprocessor\zephyr\zephyr.hex --options $opt
```

`ERASE_RANGES_TOUCHED_BY_FIRMWARE` 로 UICR 을 보존합니다. 동글의 공장 `REGOUT0 = 0xFFFFFFFC`(3.0V)를 지우면 안 됩니다.

> **검증에 쓴 동글은 클론이라 부트로더가 없었습니다** (2026-08-08 판정): 꽂으면 RGB LED 가 여러 색으로 순환하는 공장 데모 펌웨어가 돌고, USB 열거도 RESET 버튼 반응도 없음 = Nordic Open Bootloader 부재 + `PSELRESET` 미프로그램. 그래서 E73 과 동일하게 SWD 최초 플래싱이 필요했습니다. 정품처럼 부트로더가 있었다면 `nrfutil dfu usb-serial`(DFU 패키지 빌드는 `nrf5sdk-tools` 의 `nrfutil pkg`) 로 끝났을 것입니다.
> 빈 nRF52840 은 D+ 풀업을 걸지 않으므로 커널 로그에 삽입 이벤트조차 남지 않습니다 — "안 꽂힌 것"과 구분이 안 됩니다. SWD 배선/속도/전원 함정은 [../firmware/docs/build-and-flash.md §4](../firmware/docs/build-and-flash.md) 와 동일합니다.

### 1.3 열거 확인

```
1915:0000  Thread Co-Processor   Nordic Semiconductor ASA
/dev/ttyACM0
/dev/serial/by-id/usb-Nordic_Semiconductor_ASA_Thread_Co-Processor_<SN>-if00
```

**USB 허브를 거치면 실패합니다.** 동글은 기판 가장자리가 곧 커넥터라 두께가 규격보다 얇습니다. 허브에서는 디스크립터 요청 단계에서 깨지고(`장치 설명자 요청 실패`), 호스트 본체 포트에 직결하면 정상 열거됩니다. 파이에서도 본체 포트를 쓰세요.

---

## 2. 파이에 OpenThread 데몬 설치

### 2.1 빌드

```bash
sudo apt-get install -y cmake ninja-build libreadline-dev
git clone --depth 1 https://github.com/openthread/openthread.git
cd openthread
git submodule update --init --depth 1 --recursive     # mbedtls 등 - 빼면 빌드 실패

mkdir build && cd build
cmake -GNinja \
  -DOT_PLATFORM=posix \
  -DOT_DAEMON=ON \
  -DOT_PLATFORM_NETIF=ON \
  -DOT_PLATFORM_UDP=ON \
  -DOT_POSIX_RCP_HDLC_BUS=ON \
  -DOT_THREAD_VERSION=1.3 \
  -DOT_UPTIME=ON \
  ..
ninja -j4
```

산출물: `build/src/posix/ot-daemon`, `build/src/posix/ot-ctl`

빌드 함정:

* `OT_POSIX_CONFIG_RCP_BUS` 는 **제거된 옵션**입니다. `OT_POSIX_RCP_HDLC_BUS=ON` 을 쓰세요.
* **서브모듈을 안 받으면** mbedtls 타깃이 없어 configure 가 깨집니다 (`Cannot specify include directories for target "mbedx509"`).
* `OT_UPTIME=ON` 이 없으면 `OPENTHREAD_CONFIG_LOG_PREPEND_UPTIME requires OPENTHREAD_CONFIG_UPTIME_ENABLE` 로 컴파일이 실패합니다.
* `OT_SRP_SERVER=ON` 은 현재 main 에서 컴파일되지 않습니다. 이 용도에는 필요 없습니다 — 파이는 보더 라우터가 아니라 메시의 일반 노드로 참여하고, 게이트웨이가 `wpan0` 에 바인드하면 됩니다.

### 2.2 서비스 등록

```bash
sudo install -m755 build/src/posix/ot-daemon /usr/local/bin/ot-daemon
sudo install -m755 build/src/posix/ot-ctl    /usr/local/bin/ot-ctl

sudo cp systemd/ot-daemon.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ot-daemon
```

> 동글이 여러 개거나 다른 CDC 장치가 붙는 환경이면 서비스 파일의 `/dev/ttyACM0` 대신 `/dev/serial/by-id/...` 경로로 고정하세요.

---

## 3. Thread 네트워크 구성

**노드 펌웨어 `../firmware/prj.conf` 의 크리덴셜과 정확히 일치해야 합니다.**

> **주의: 이 빌드의 ot-daemon 은 데이터셋을 재부팅 후 유지하지 못합니다** (2026-08-09 실측 — 재부팅 뒤 `dataset active` 가 `NotFound`, `state` 가 `disabled`). POSIX 설정 저장 경로가 휘발성인 탓으로 보입니다. 파이를 재부팅했다면 아래 블록을 다시 실행하세요 (같은 값으로 다시 커밋하면 노드는 재커미셔닝 없이 그대로 붙습니다). 게이트웨이 브리지는 wpan0 이 다시 서면 자동으로 멀티캐스트에 재가입합니다.

```bash
sudo ot-ctl dataset init new
sudo ot-ctl dataset channel 15
sudo ot-ctl dataset panid 0xabcd
sudo ot-ctl dataset networkname SmartShelf
sudo ot-ctl dataset extpanid 1111111122222222
sudo ot-ctl dataset networkkey 00112233445566778899aabbccddeeff
# 아래 두 줄이 중요 — 없으면 예전 데이터셋을 NVS 에 가진 노드가 조용히 고장난다 (아래 함정 참고)
sudo ot-ctl dataset meshlocalprefix fd59:ff6a:a426:1e98::
sudo ot-ctl dataset activetimestamp 20260809    # 재구성할 때마다 이전보다 큰 값으로 (예: YYYYMMDDHH)
sudo ot-ctl dataset commit active
sudo ot-ctl ifconfig up
sudo ot-ctl thread start
```

### 함정 — stale 데이터셋을 가진 노드는 "붙는데 통신이 안 된다" (2026-08-09 실측)

노드는 OpenThread 데이터셋을 자기 NVS 에 저장하고, 저장본이 있으면 `prj.conf` 값 대신 그걸 씁니다.
데이터셋에는 눈에 안 보이는 **mesh-local prefix** 가 들어 있는데, `dataset init new` 는 매번 새 랜덤 prefix 와 **Active Timestamp 1** 을 만듭니다. 그 결과:

* 예전 네트워크의 데이터셋(prefix `fd78:...`)을 저장한 노드가 새 네트워크(prefix `fd59:...`)에 붙으면 — 채널/키/PAN 이 같아서 `child` 까지는 정상 — **주소는 옛 prefix 로 만들어져 응답 경로가 없고 CoAP 이 전부 유실**됩니다.
* 리더는 자기보다 **타임스탬프가 큰** 데이터셋만 전파하므로, 양쪽 다 1 이면 영영 동기화되지 않습니다.

증상: `ot state` 는 `child`, 게이트웨이에는 접촉 0. 노드 셸 `ot ipaddr` 의 prefix 가 파이의 `wpan0` 와 다르면 이것입니다.
해결: 파이에서 타임스탬프만 올려 커밋하면 전 노드에 자동 전파됩니다 (노드 재부팅 불필요, 실측 수 초 내 복구).

```bash
sudo ot-ctl dataset init active
sudo ot-ctl dataset activetimestamp 20260810   # 기존보다 큰 값
sudo ot-ctl dataset commit active
```

위 구성 블록처럼 prefix 를 고정하고 타임스탬프를 항상 증가시키면, 파이 재부팅으로 데이터셋을 다시 만들 때도 노드들이 자동으로 따라옵니다.

| 항목 | 노드 Kconfig (`prj.conf`) | ot-ctl |
|---|---|---|
| 채널 | `OPENTHREAD_CHANNEL=15` | `channel 15` |
| PAN ID | `OPENTHREAD_PANID=43981` | `panid 0xabcd` |
| 네트워크명 | `OPENTHREAD_NETWORK_NAME="SmartShelf"` | `networkname SmartShelf` |
| Ext PAN ID | `"11:11:11:11:22:22:22:22"` | `extpanid 1111111122222222` |
| 네트워크 키 | `"00:11:22:...:ee:ff"` | `networkkey 00112233...eeff` |

### 확인

```bash
sudo ot-ctl state          # leader (첫 기동) — 노드가 붙으면 child table 에 나타남
sudo ot-ctl child table    # 붙은 노드 목록
ip -brief addr show wpan0  # fd..::/64 IPv6 주소
```

실측 (노드 2대):

```
| ID | RLOC16 | Age | LQ In | Extended MAC     |   RSSI
|  1 | 0x3801 | 131 |     3 | ca10ad373b79d631 |   -71
|  2 | 0x3802 |  56 |     3 | aef733fdfd875668 |   -63
```

노드 쪽에서는 부팅 78 ms 만에 `Thread network connected` 가 뜨고 `ot state` 가 `child` 가 됩니다.

---

## 4. Python 게이트웨이 (gateway.py)

CoAP(메시) ↔ 웹 서버 HTTP(LAN) 브리지입니다. 하는 일:

* **무게** — 노드의 `POST shelf/weight` (JSON: `id`, `seq`, `raw`, `mg`, `mv`) 를 받아 웹 서버 `POST /api/weight/{선반ID}` 로 전달. `mg → g` 환산, 배터리 `mV → %` 환산(펌웨어 `shelf battery` 와 같은 3.3–4.2 V 선형 추정, USB 전원(>4.4 V)·측정 실패는 미보고) 포함.
* **LED** — 노드의 `GET shelf/led?id=...` (700 ms 주기, **응답 데드라인 400 ms**) 에 캐시로 즉시 응답. 캐시는 백그라운드에서 웹 서버 `GET /api/led/{선반ID}` 를 1초 주기로 읽어 갱신하며, 이 폴링이 웹 서버의 온라인 판정(하트비트)도 겸합니다 — 기존 ESP8266 의 1초 폴링과 같은 효과. CoAP 응답 경로에서는 절대 HTTP 를 기다리지 않습니다.
* **탐색** — `ff03::1` (realm-local all-nodes) 멀티캐스트 그룹에 가입해 노드의 게이트웨이 탐색 요청을 받습니다. 노드는 LED 폴에 응답한 유니캐스트 주소를 기억합니다. **wpan0 은 ot-daemon 재시작 때 새로 만들어져 가입이 조용히 사라지므로, 10초마다 재가입해 유지합니다.**
* **선반 ID 매핑** — 노드 하드웨어 ID(16 hex) → 웹 서버 선반 ID 를 `gateway.toml` 의 `[nodes]` 로 매핑. 매핑이 없으면 하드웨어 ID 그대로 서버에 올라가고, 웹의 미등록 기기 흐름으로 등록하면 됩니다.

CoAP 는 외부 라이브러리 없이 `shelfcoap.py` 로 직접 처리합니다 — 노드가 쓰는 부분집합(CON GET + URI_PATH/URI_QUERY, NON POST + JSON)만 구현했고, 노드가 재전송을 하지 않으므로(응답 타임아웃 400 ms < CoAP 재전송 타이머) 중복 제거·재전송은 필요 없습니다. 노드 쪽 규격 원문: [../firmware/docs/build-and-flash.md §7](../firmware/docs/build-and-flash.md).

### 4.1 설치 (파이)

```bash
sudo mkdir -p /opt/shelf-gateway
sudo cp gateway.py shelfcoap.py gateway.example.toml pyproject.toml /opt/shelf-gateway/
cd /opt/shelf-gateway
sudo python3 -m venv .venv
sudo .venv/bin/pip install aiohttp
sudo cp gateway.example.toml gateway.toml
sudo nano gateway.toml        # base_url 을 웹 서버 PC 주소로
```

(uv 를 쓴다면 `uv sync` 후 `uv run gateway.py` 도 됩니다 — 의존성은 `pyproject.toml` 에 있습니다.)

### 4.2 실행 / 확인

```bash
.venv/bin/python gateway.py            # 포그라운드, 로그 확인용
.venv/bin/python gateway.py -v         # 디버그 로그
python3 -m unittest                    # 단위 테스트 (의존성 불필요)
```

정상 기동 로그:

```
CoAP 서버 시작: [::]:5683, 웹 서버: http://192.168.0.8:8000
멀티캐스트 ff03::1 @ wpan0 가입
상태 조회 HTTP: http://0.0.0.0:8080/status
새 노드 1e0582a6f932714e -> 선반 ID '...' (fdde:ad00:...)
무게 보고 1e0582a6f932714e('...') seq=12 raw=1140384 -> 11404 g, 4855 mV
```

상태 조회 (노드별 무게/배터리/LED/최근 접촉/서버 통신 상태):

```bash
curl http://<파이>:8080/status
```

### 4.3 서비스 등록

```bash
sudo cp systemd/shelf-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now shelf-gateway
journalctl -u shelf-gateway -f
```

### 4.4 트러블슈팅

| 증상 | 확인 |
|---|---|
| `멀티캐스트 가입 실패 ... wpan0 대기` 반복 | `systemctl status ot-daemon`, `ip link show wpan0`. ot-daemon 이 뜨면 자동 복구됩니다. |
| 노드가 게이트웨이를 못 찾음 (`ot state` 는 `child`) | 게이트웨이 로그에 폴이 안 보이면 멀티캐스트 가입 여부와 `base_path`(기본 `shelf`) 일치 확인. 노드에서 `ot ping ff03::1` 로 도달성 확인. |
| 노드는 붙는데 LED 가 안 켜짐 | `curl http://<파이>:8080/status` 로 `server_ok` 확인. `false` 면 `gateway.toml` 의 `base_url`/방화벽(웹 서버 PC 인바운드 8000) 문제. 웹 서버는 반드시 `--host 0.0.0.0` 으로 실행. |
| 무게가 웹에 안 뜸 | 게이트웨이 로그의 `무게 보고` 라인 유무로 노드↔게이트웨이 문제인지, `웹 서버 통신 실패` 라인으로 게이트웨이↔서버 문제인지 구분. |
| 웹에서 노드가 오프라인 | 노드가 죽었거나(마지막 접촉 15초 초과 시 하트비트 대행 중단) 게이트웨이가 내려간 것. `/status` 의 `last_seen_age_s` 확인. |

---

## 5. 웹 서버 연동 규격 (참고)

| 방향 | CoAP (메시) | HTTP (LAN) |
|---|---|---|
| 무게 보고 | `POST shelf/weight` NON, `{"id","seq","raw","mg","mv"}` | `POST /api/weight/{선반ID}` `{"value": g, "battery": %}` |
| LED 명령 | `GET shelf/led?id=<hwid>` CON → `"1"`/`"0"` | `GET /api/led/{선반ID}` → `{"on": true/false}` (1초 캐시) |

배터리 % 는 서버 `battery` 필드(웹 대시보드 표시)로 들어갑니다. USB 전원으로 판정된 보고(>4.4 V)에서는 battery 를 보내지 않아 마지막 배터리 값이 유지됩니다.
