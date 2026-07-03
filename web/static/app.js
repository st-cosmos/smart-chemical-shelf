const lamp1 = document.getElementById("lamp-1");
const status1 = document.getElementById("status-1");
const led1Group = document.getElementById("led1-group");

// Potentiometer / Weight Scale elements
const weightValue = document.getElementById("weight-value");
const rawVal = document.getElementById("raw-val");
const weightTime = document.getElementById("weight-time");
const weightStatusBadge = document.getElementById("weight-status-badge");
const weightStatusText = document.getElementById("weight-status-text");
const progressBarFill = document.getElementById("progress-bar-fill");


// 서버에서 받은 LED 상태로 화면을 갱신합니다.
function renderLed(data) {
  // LED 1 갱신
  const led1 = data.led1;
  lamp1.textContent = led1.on ? "ON" : "OFF";
  lamp1.className = "lamp " + (led1.on ? "on" : "off");
  if (led1.on) {
    led1Group.classList.add("led-active");
  } else {
    led1Group.classList.remove("led-active");
  }
  status1.textContent = `Last Change: ${led1.by} at ${led1.time}`;
  
  // LED 카드 전체 활성화 효과 (어느 하나라도 켜져있으면)
  const ledCard = document.getElementById("led-card");
  if (led1.on) {
    ledCard.classList.add("led-active");
  } else {
    ledCard.classList.remove("led-active");
  }
}



// 현재 LED 상태를 가져와 화면을 갱신합니다. (polling)
async function refreshLed() {
  try {
    const res = await fetch("/api/led");
    if (res.ok) {
      renderLed(await res.json());
    }
  } catch (err) {
    console.error("Error fetching LED state:", err);
  }
}





// 가변저항 및 무게 상태 화면 갱신
function renderPotentiometer(data) {
  const value = data.value;
  // 0-1023 가변저항 값을 0.0 - 10.0 kg 무게로 환산
  const weight = (value * 10.0 / 1023).toFixed(1);
  weightValue.textContent = weight;
  rawVal.textContent = value;
  weightTime.textContent = data.time || "-";

  // 상태 배지 클래스 및 텍스트 갱신
  weightStatusBadge.className = "weight-status-badge";
  if (data.status === "증가") {
    weightStatusBadge.classList.add("status-increase");
    weightStatusText.textContent = "▲ 무게 증가";
  } else if (data.status === "감소") {
    weightStatusBadge.classList.add("status-decrease");
    weightStatusText.textContent = "▼ 무게 감소";
  } else {
    weightStatusBadge.classList.add("status-stable");
    weightStatusText.textContent = "● 무게 유지";
  }

  // 프로그레스 바 너비 설정
  const percent = (value / 1023 * 100).toFixed(1);
  progressBarFill.style.width = `${percent}%`;
}

// 가변저항 상태 가져오기
async function refreshPotentiometer() {
  try {
    const res = await fetch("/api/potentiometer");
    if (res.ok) {
      renderPotentiometer(await res.json());
    }
  } catch (err) {
    console.error("Error fetching potentiometer state:", err);
  }
}



// 초기 동기화 및 폴링 설정
function init() {
  refreshLed();
  refreshPotentiometer();
  
  // 1초마다 동기화
  setInterval(() => {
    refreshLed();
    refreshPotentiometer();
  }, 1000);
}

init();
