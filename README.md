# Smart Chemical Shelf

스마트 화학 시약장 프로젝트입니다.

## 프로젝트 구성
- `android-app`: 안드로이드 애플리케이션 (시약병 라벨 인식 및 LED 제어)
- `firmware`: 펌웨어 소스 코드
- `web`: 웹 대시보드/서비스 소스 코드

## Android Application (`android-app`)
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
