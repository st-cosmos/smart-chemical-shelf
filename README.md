# Smart Chemical Shelf (스마트 화학 시약장)

스마트 화학 시약장 프로젝트입니다. E73-2G4M08S1C(nRF52840) 커스텀 보드 선반 노드가 **Thread 메시**로 게이트웨이(라즈베리파이)에 붙고, 게이트웨이가 웹 대시보드 서버와 통신하여 로드셀 무게 모니터링·배터리 잔량 보고·LED 제어를 수행합니다.

```
[선반 노드 xN: E73 + CS1237 로드셀] --Thread/CoAP--> [게이트웨이: 파이 + RCP 동글] --HTTP--> [web 서버] <-- 브라우저/안드로이드 앱
```

## 프로젝트 구성
- `android-app`: 안드로이드 애플리케이션 (시약병 라벨 인식 및 LED 제어)
- `firmware`: 선반 노드 펌웨어 — E73-2G4M08S1C(nRF52840) + CS1237 로드셀, nRF Connect SDK(Zephyr) + OpenThread + CoAP. 이전 ESP8266(PlatformIO) 버전은 git 히스토리에 있습니다.
- `gateway`: Thread 게이트웨이 — 라즈베리파이 + nRF52840 RCP 동글(ot-daemon) + CoAP↔웹서버 브리지(`gateway.py`)
- `web`: 웹 대시보드 및 FastAPI 서버 소스 코드 (uv 패키지 관리 환경)

---

## 1. Web 대시보드 서버 실행

웹 서버는 REST API 제공 및 대시보드 프론트엔드 정적 파일 서빙을 동시에 담당합니다.
프로젝트는 [uv](https://docs.astral.sh/uv/)로 관리됩니다.

### 실행 방법
`web` 디렉토리로 이동한 뒤, 아래 명령어로 서버를 실행합니다.

```bash
# 1. web 폴더로 이동
cd web

# 2. 로컬 개발 서버 실행 (기본 localhost:8000)
uv run uvicorn main:app --reload

# 3. 외부 기기(ESP8266 등) 접속 허용을 위한 0.0.0.0 호스팅 실행 (중요)
uv run uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

> [!IMPORTANT]
> 게이트웨이(라즈베리파이)가 PC의 웹 서버에 접속하려면 반드시 `--host 0.0.0.0` 옵션을 지정하여 실행해야 하며, 게이트웨이와 PC가 **동일한 LAN**에 연결되어 있어야 합니다.
> Windows의 경우 방화벽에서 8000번 포트의 인바운드 허용이 필요할 수 있습니다.

실행 후 브라우저에서 `http://localhost:8000` 또는 `http://<PC의_IP_주소>:8000`으로 접속하여 실시간 대시보드를 확인할 수 있습니다.

---

## 2. Firmware (선반 노드, E73-2G4M08S1C) — [`firmware/`](firmware)

nRF Connect SDK v3.4.0 (Zephyr) + OpenThread + CoAP 기반이며, VS Code 의 nRF Connect 확장 또는 west CLI 로 빌드합니다.

```powershell
cd firmware
west build -b nrf52840dk/nrf52840 --sysbuild -p always .
```

- **최초 1회는 SWD 플래싱**이 필요합니다 (nRF52840 은 공장 USB 부트로더가 없음). `UICR.REGOUT0` 3.3V 설정 등 순서 함정이 많으니 반드시 [firmware/docs/build-and-flash.md](firmware/docs/build-and-flash.md) 를 따르세요.
- 이후 업데이트는 **USB-C 만으로** 가능합니다 (MCUboot 시리얼 리커버리 + mcumgr, 검증 완료).
- Wi-Fi 설정 같은 것은 없습니다. Thread 크리덴셜(`prj.conf`)이 게이트웨이의 네트워크와 일치하면 자동으로 붙습니다.
- 로드셀 영점/캘리브레이션은 USB-C 셸에서 `shelf tare` / `shelf cal <g>` 로 수행하고 NVS 에 영구 저장됩니다.
- 핀: LED P0.06 / CS1237 DOUT P0.08, SCLK P1.09 / 로드셀 전원 P0.12 / 배터리 전압 SAADC VDDH÷5 내부 탭

자세한 내용은 [firmware/README.md](firmware/README.md) 참고.

---

## 3. Gateway (Thread ↔ 웹 서버 브리지) — [`gateway/`](gateway)

라즈베리파이 + nRF52840 동글(RCP) 로 Thread 네트워크를 만들고, 노드의 CoAP 트래픽을 웹 서버 HTTP API 로 중개합니다.

1. 동글에 `ot-rcp` 펌웨어 굽기 → 파이에서 `ot-daemon` 실행 (`wpan0` 생성)
2. `ot-ctl` 로 Thread 네트워크 데이터셋 구성 (노드 크리덴셜과 일치, 최초 1회)
3. `gateway.py` 실행 — 무게 보고(g + 배터리 %)를 `POST /api/weight/{id}` 로 전달하고, `GET /api/led/{id}` 를 1초 주기로 읽어 노드 LED 폴링(700 ms)에 캐시로 응답 (하트비트 겸용)

설치·검증 절차와 트러블슈팅은 [gateway/README.md](gateway/README.md) 참고.

---

## 4. Android Application (`android-app`)

CameraX와 ML Kit OCR(KoreanTextRecognizer)을 이용하여 시약병의 라벨 텍스트를 인식하고, 특정 시약 키워드가 감지되면 FastAPI 기반의 LED 제어 서버에 PUT 요청을 보냅니다.

### 주요 기능
- **CameraX Preview & Image Analysis**: 실시간으로 카메라 프레임을 읽어옵니다.
- **ML Kit Text Recognition**: 한국어/영어/숫자를 실시간으로 인식합니다.
- **특정 키워드 감지**: 
  - 감지 키워드 리스트: `ETHANOL`, `METHANOL`, `ACETONE`, `ACID`, `WATER`, `SODIUM`, `에탄올`, `메탄올`, `아세톤`, `염산`, `황산`, `질산`, `수산화나트륨`
  - 키워드가 인식되면 서버에 `on = true` 상태를 전송하여 LED를 켭니다.
  - 키워드가 감지되지 않으면 `on = false` 상태를 전송하여 LED를 끕니다.
- **Retrofit 통신**: `PUT /api/led` 엔드포인트를 통해 LED 상태 변경 명령을 전송합니다.

### 연동 서버 설정
`MainActivity.kt` 의 `BASE_URL`을 LED 제어 서버 주소로 변경하여 빌드하십시오.
(예: 에뮬레이터 `http://10.0.2.2:8000/`, 실제 기기 `http://<PC-IP>:8000/`)

---

## 주요 기능 흐름

1. **LED 상태 동기화**: 웹 대시보드에서 LED 상태를 제어(`PUT /api/led/{id}`)하거나 안드로이드 앱에서 라벨 인식을 수행하면, 게이트웨이가 1초 간격으로 `GET /api/led/{id}` 를 읽어 캐시하고(서버 쪽 온라인 하트비트 겸용), 노드는 700 ms 간격의 CoAP 폴링으로 그 상태를 받아 물리 LED 를 켜고 끕니다.
2. **무게 업로드**: 노드가 10초마다 로드셀 전원을 켜 CS1237 로 측정하고(그 외 시간에는 전원 차단), 직전 보고 대비 임계값 이상 변했을 때만 CoAP 로 게이트웨이에 보고합니다 (변화가 없어도 5분마다 하트비트). 게이트웨이는 이를 `POST /api/weight/{id}` (그램 + 배터리 %) 로 전달하고, 웹 페이지는 무게 그래프·잔량 및 반입/반출 세션 판정에 사용합니다.
3. **배터리 잔량**: 노드가 보고마다 SAADC 내부 VDDH/5 탭으로 배터리 전압을 함께 보내고, 게이트웨이가 % 로 환산해 서버 `battery` 필드로 올립니다 (USB 전원 중에는 미보고로 마지막 값 유지).
