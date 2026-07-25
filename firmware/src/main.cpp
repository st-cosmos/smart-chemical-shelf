#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>
#include <EEPROM.h>
#include "HX711.h"
#include "config.h"

// 서버의 LED 상태를 읽어 물리 LED에 반영하는 예제


const int LED_PIN_1 = 5;    // LED 1 핀 (D1, GPIO 5)


// 로드셀 핀 설정 (HX711)
// DOUT 핀 -> D6 (GPIO12)
// SCK 핀  -> D7 (GPIO13)
const int LOADCELL_DOUT_PIN = 12;
const int LOADCELL_SCK_PIN = 13;

HX711 scale;

// --- [ EEPROM 설정 ] ---
const int EEPROM_SIZE = 32;
const uint32_t EEPROM_MAGIC = 0xABCD1235; // 매직 넘버 변경으로 이전 레이아웃 데이터 초기화 및 강제 재설정
const int ADDR_MAGIC = 0;
const int ADDR_FACTOR = 4;
const int ADDR_OFFSET = 8; // 영점 오프셋 주소 추가

float calibration_factor = 420.0f; // 초기 기본 팩터값 (로드셀 사양에 따라 다름)
long tare_offset = 0;              // 영점 오프셋 값
int lastRawWeight = 0;              // 직전 측정된 무게 (안정화 판단용)
int lastSentWeightValue = -99999;   // 마지막으로 서버에 전송한 확정 무게
unsigned long lastWeightChangeTime = 0; // 마지막으로 무게 변화가 감지된 시각
const unsigned long stabilityDelay = 500; // 무게가 안정될 때까지 기다릴 시간 (0.5초)
bool isChanging = false;            // 무게 변화 상태 여부
unsigned long lastWeightPoll = 0;    // 마지막으로 무게를 읽은 시각
const unsigned long weightPollInterval = 300; // 무게 확인 주기 (300ms)

unsigned long lastPoll = 0; // 마지막으로 LED 상태를 확인한 시각





void sendWeightValue(int value) {
  HTTPClient http;
  WiFiClient client;
  http.begin(client, String(SERVER_URL) + "/api/weight/" + DEVICE_ID);
  http.addHeader("Content-Type", "application/json");
  
  String jsonPayload = "{\"value\":" + String(value) + "}";
  int httpResponseCode = http.POST(jsonPayload);
  
  if (httpResponseCode > 0) {
    Serial.println("Weight value " + String(value) + " g sent for " + DEVICE_ID + ". Response: " + String(httpResponseCode));
  } else {
    Serial.println("Error sending weight value: " + String(httpResponseCode));
  }
  http.end();
}

void checkWeight() {
  if (millis() - lastWeightPoll > weightPollInterval) {
    lastWeightPoll = millis();
    if (scale.is_ready()) {
      // 10Hz 속도 기준 3회 평균(약 300ms)으로 변경하여 반응성을 높임
      float weight = scale.get_units(3); 
      int currentVal = (int)weight;

      // 3g 이상 무게 변화가 생기면 변화 중인 것으로 간주
      if (abs(currentVal - lastRawWeight) >= 3) {
        lastRawWeight = currentVal;
        lastWeightChangeTime = millis();
        isChanging = true;
      }
      
      // 무게가 변화한 상태에서 설정한 시간(stabilityDelay) 동안 추가 변화가 없으면 최종 안정화로 판단
      if (isChanging && (millis() - lastWeightChangeTime > stabilityDelay)) {
        isChanging = false;
        
        // 안정화된 최종 무게가 이전에 서버로 보낸 무게와 5g 이상 차이가 나거나 처음 보낼 때 전송
        if (lastSentWeightValue == -99999 || abs(currentVal - lastSentWeightValue) > 5) {
          lastSentWeightValue = currentVal;
          sendWeightValue(currentVal);
        }
      }
    }
  }
}



void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN_1, OUTPUT);
  digitalWrite(LED_PIN_1, LOW);

  // HX711 초기화
  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);

  // 로드셀 연결 대기 (최대 3초)
  Serial.println("로드셀 연결 상태 확인 중...");
  unsigned long start = millis();
  bool connected = false;
  while (millis() - start < 3000) {
    if (scale.is_ready()) {
      connected = true;
      break;
    }
    delay(100);
  }
  
  if (connected) {
    Serial.println("로드셀 연결 성공!");
  } else {
    Serial.println("경고: 로드셀을 찾을 수 없습니다. (배선을 확인하세요)");
  }

  // EEPROM 초기화 및 설정 로드
  EEPROM.begin(EEPROM_SIZE);
  
  uint32_t magic;
  EEPROM.get(ADDR_MAGIC, magic);
  if (magic == EEPROM_MAGIC) {
    EEPROM.get(ADDR_FACTOR, calibration_factor);
    EEPROM.get(ADDR_OFFSET, tare_offset);

    // 안전장치: 로드된 팩터가 유효하지 않은 값(0 또는 NaN)인 경우 기본값으로 복구
    if (isnan(calibration_factor) || calibration_factor == 0.0f) {
      calibration_factor = 420.0f;
      Serial.println("[EEPROM] 경고: 로드된 캘리브레이션 팩터가 유효하지 않아 기본값(420.0)으로 복원합니다.");
    }

    Serial.println("[EEPROM] 기존 캘리브레이션 팩터 및 영점 로드 완료.");
    Serial.print(" - Calibration Factor: ");
    Serial.println(calibration_factor, 2);
    Serial.print(" - Tare Offset: ");
    Serial.println(tare_offset);

    // 로드셀에 설정값 적용
    scale.set_scale(calibration_factor);
    scale.set_offset(tare_offset);
  } else {
    Serial.println("[EEPROM] 저장된 데이터가 없어 초기 설정을 수행합니다.");
    
    // 팩터 기본값 설정
    scale.set_scale(calibration_factor);
    
    if (connected) {
      scale.tare(); // 초기 영점 조절 (Tare)
      tare_offset = scale.get_offset();
      Serial.println("로드셀 초기 영점 조절 완료.");
      
      // 설정 저장
      EEPROM.put(ADDR_MAGIC, EEPROM_MAGIC);
      EEPROM.put(ADDR_FACTOR, calibration_factor);
      EEPROM.put(ADDR_OFFSET, tare_offset);
      EEPROM.commit();
      Serial.println("[EEPROM] 초기 설정을 플래시에 저장했습니다.");
    } else {
      Serial.println("초기 설정을 생략합니다. (로드셀 없음)");
    }
  }

  // WiFi 연결
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("WiFi 연결 중");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\n연결됨, IP: " + WiFi.localIP().toString());
}

// 서버에서 디바이스(LED) 상태를 받아와 핀에 반영
void pollLed() {
  HTTPClient http;
  WiFiClient client;
  http.begin(client, String(SERVER_URL) + "/api/led/" + DEVICE_ID);

  int httpCode = http.GET();
  if (httpCode == 200) {
    String body = http.getString();
    bool led1_on = body.indexOf("\"on\":true") >= 0;
    
    digitalWrite(LED_PIN_1, led1_on ? HIGH : LOW);
  } else {
    Serial.print("요청 실패 — 서버 응답 코드: ");
    Serial.println(httpCode);
  }
  http.end();
}

void loop() {


  // 무게 상태 감지 (로드셀)
  checkWeight();

  // 1초마다 서버의 LED 상태를 확인해 물리 LED에 반영
  if (millis() - lastPoll > 1000) {
    lastPoll = millis();
    pollLed();
  }
}
