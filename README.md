# Smart Chemical Shelf (스마트 화학 시약장)

스마트 화학 시약장 프로젝트입니다. 웹 대시보드와 ESP8266 펌웨어가 유기적으로 통신하여 LED 제어 및 가변저항(무게 센서 대용) 모니터링을 수행합니다.

## 프로젝트 구성
- `android-app`: 안드로이드 애플리케이션 (시약병 라벨 인식 및 LED 제어)
- `firmware`: ESP8266용 펌웨어 소스 코드 (PlatformIO 빌드 환경)
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
> ESP8266 보드가 PC의 웹 서버에 접속하려면 반드시 `--host 0.0.0.0` 옵션을 지정하여 실행해야 하며, 보드와 PC가 **동일한 Wi-Fi 네트워크**에 연결되어 있어야 합니다.
> Windows의 경우 방화벽에서 8000번 포트의 인바운드 허용이 필요할 수 있습니다.

실행 후 브라우저에서 `http://localhost:8000` 또는 `http://<PC의_IP_주소>:8000`으로 접속하여 실시간 대시보드를 확인할 수 있습니다.

---

## 2. Firmware (ESP8266) 설정 및 업로드

VS Code와 PlatformIO IDE 환경에서 개발을 진행합니다.

### 1) 환경 설정 (`config.h` 작성)
1. `firmware/include/config.example.h` 파일을 복사하여 `firmware/include/config.h` 파일을 생성합니다. (`config.h`는 git 관리 대상에서 제외됩니다.)
2. 본인의 Wi-Fi SSID, 패스워드와 서버 URL(PC의 로컬 IP 주소)을 설정합니다.

```cpp
#pragma once

#define WIFI_SSID     "Your-WiFi-Name"
#define WIFI_PASSWORD "Your-WiFi-Password"

// FastAPI 서버를 실행 중인 PC의 로컬 IP 주소와 포트 (예시)
#define SERVER_URL    "http://192.168.0.10:8000"
```

### 2) 핀 맵핑 안내
기본 회로 연결 상태는 다음과 같이 구성되어 있습니다. 본인 환경에 맞춰 `main.cpp`에서 수정할 수 있습니다.
- **LED 1**: D1 (GPIO 5) -> 저항 -> LED -> GND
- **LED 2**: D2 (GPIO 4) -> 저항 -> LED -> GND
- **Switch 1**: D7 (GPIO 13) -> 스위치 -> HIGH (풀다운 저항 연결)
- **Switch 2**: D8 (GPIO 15) -> 스위치 -> HIGH (풀다운 저항 연결)
- **가변저항 (Potentiometer)**: A0 (Analog Input)

### 3) 빌드 및 업로드
VS Code의 PlatformIO 인터페이스를 사용하거나 CLI에서 다음 명령을 실행합니다.

```bash
# firmware 폴더로 이동
cd firmware

# 빌드 및 업로드
pio run --target upload

# 시리얼 모니터 확인
pio device monitor
```

---

## 3. Android Application (`android-app`)

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

1. **LED 상태 동기화**: 웹 대시보드에서 LED 상태를 제어(`PUT /api/led`)하거나 안드로이드 앱에서 라벨 인식을 수행하면, ESP8266 보드가 1초 간격으로 `GET /api/led` 요청을 보내 상태를 확인한 후 실제 물리 LED의 ON/OFF에 반영합니다.
2. **가변저항 값 업로드**: ESP8266 보드가 주기적으로 아날로그 핀(`A0`) 값을 측정하여 값에 유의미한 변화가 있을 때 서버로 `POST /api/potentiometer` 요청을 보냅니다. 웹 페이지는 이를 시각적인 무게 그래프 및 텍스트로 환산하여 보여줍니다.
3. **스위치 이벤트 로그**: 보드의 스위치가 눌리면 `POST /api/switch` 요청을 보내 서버에 로그를 남기며, 웹 대시보드에 실시간으로 로그 목록이 갱신됩니다.
