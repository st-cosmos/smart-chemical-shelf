#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>
#include "config.h"

// 서버의 LED 상태를 읽어 물리 LED에 반영하는 예제

const int LED_PIN_1 = 5;    // LED 1 핀 (D1, GPIO 5)


const int POT_PIN = A0;     // 가변저항 아날로그 핀 (A0)
int lastPotValue = -1;      // 이전 가변저항 값
unsigned long lastPotPoll = 0; // 마지막으로 아날로그 값을 읽은 시각
const unsigned long potPollInterval = 300; // 아날로그 값 확인 주기 (300ms)

unsigned long lastPoll = 0; // 마지막으로 LED 상태를 확인한 시각





void sendPotentiometerValue(int value) {
  HTTPClient http;
  WiFiClient client;
  http.begin(client, String(SERVER_URL) + "/api/potentiometer");
  http.addHeader("Content-Type", "application/json");
  
  String jsonPayload = "{\"value\":" + String(value) + "}";
  int httpResponseCode = http.POST(jsonPayload);
  
  if (httpResponseCode > 0) {
    Serial.println("Potentiometer value " + String(value) + " sent. Response: " + String(httpResponseCode));
  } else {
    Serial.println("Error sending potentiometer value: " + String(httpResponseCode));
  }
  http.end();
}

void checkPotentiometer() {
  if (millis() - lastPotPoll > potPollInterval) {
    lastPotPoll = millis();
    int currentVal = analogRead(POT_PIN);
    
    // 이전 값과 임계값(8) 이상 차이가 나거나 처음 보낼 때 전송
    if (lastPotValue == -1 || abs(currentVal - lastPotValue) > 8) {
      lastPotValue = currentVal;
      sendPotentiometerValue(currentVal);
    }
  }
}



void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN_1, OUTPUT);
  digitalWrite(LED_PIN_1, LOW);



  // WiFi 연결
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("WiFi 연결 중");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\n연결됨, IP: " + WiFi.localIP().toString());
}

// 서버에서 LED 상태를 받아와 핀에 반영
void pollLed() {
  HTTPClient http;
  WiFiClient client;
  http.begin(client, String(SERVER_URL) + "/api/led");

  int httpCode = http.GET();
  if (httpCode == 200) {
    String body = http.getString();
    bool led1_on = body.indexOf("\"led1_on\":true") >= 0;
    
    digitalWrite(LED_PIN_1, led1_on ? HIGH : LOW);
    
    Serial.print("LED1: ");
    Serial.println(led1_on ? "ON" : "OFF");
  } else {
    Serial.print("요청 실패 — 서버 응답 코드: ");
    Serial.println(httpCode);
  }
  http.end();
}

void loop() {


  // 가변저항 상태 감지
  checkPotentiometer();

  // 1초마다 서버의 LED 상태를 확인해 물리 LED에 반영
  if (millis() - lastPoll > 1000) {
    lastPoll = millis();
    pollLed();
  }
}
