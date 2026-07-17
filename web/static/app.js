// --- Application State ---
let activeTab = "dashboard-tab";
let checkinStream = null;
let checkoutStream = null;
let ocrChemicals = [];
let registeredUsers = [];
let activeChemicals = [];
let espStates = {};
let previousSessionActive = false;

// --- DOM Elements ---
const clockDisplay = document.getElementById("clock-display");
const activeUserName = document.getElementById("active-user-name");
const registeredUsersList = document.getElementById("registered-users-list");
const checkinOcrInput = document.getElementById("checkin-ocr-input");
const checkoutOcrInput = document.getElementById("checkout-ocr-input");
const chemicalsListBody = document.getElementById("chemicals-list-body");
const chemicalCountBadge = document.getElementById("chemical-count-badge");
const searchChemInput = document.getElementById("search-chem-input");
const btnSearchChemicals = document.getElementById("btn-search-chemicals");
const searchResultsList = document.getElementById("search-results-list");

// --- Tab Navigation Setup ---
document.querySelectorAll(".nav-btn").forEach(btn => {
  btn.addEventListener("click", () => {
    const targetTab = btn.getAttribute("data-tab");
    switchTab(targetTab);
  });
});

function switchTab(tabId) {
  // Hide all tab panes
  document.querySelectorAll(".tab-pane").forEach(pane => {
    pane.classList.remove("active");
  });
  
  // Deactivate all nav buttons
  document.querySelectorAll(".nav-btn").forEach(btn => {
    btn.classList.remove("active");
    if (btn.getAttribute("data-tab") === tabId) {
      btn.classList.add("active");
    }
  });

  // Show selected tab pane
  const activePane = document.getElementById(tabId);
  if (activePane) {
    activePane.classList.add("active");
  }
  
  activeTab = tabId;

  // Handle camera streams based on active tab
  if (tabId === "checkin-tab") {
    // Keep checkin camera streaming if active, turn off checkout
    stopCamera("checkout");
  } else if (tabId === "checkout-tab") {
    // Keep checkout camera streaming if active, turn off checkin
    stopCamera("checkin");
  } else {
    // Turn off both cameras to save resources
    stopCamera("checkin");
    stopCamera("checkout");
  }
}

// --- Digital Clock ---
function updateClock() {
  const now = new Date();
  clockDisplay.textContent = now.toLocaleTimeString("ko-KR", { hour12: false }) + " | " + now.toLocaleDateString("ko-KR");
}
setInterval(updateClock, 1000);
updateClock();

// --- Toast Notification System ---
function showToast(message, type = "info") {
  const container = document.getElementById("toast-container");
  const toast = document.createElement("div");
  toast.className = `toast toast-${type} fade-in`;
  
  let icon = "🔔";
  if (type === "success") icon = "✅";
  if (type === "error") icon = "⚠️";
  
  toast.innerHTML = `
    <span class="toast-icon">${icon}</span>
    <span class="toast-message">${message}</span>
  `;
  
  container.appendChild(toast);
  
  // Remove toast after animation
  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(-20px)";
    setTimeout(() => {
      toast.remove();
    }, 400);
  }, 4000);
}

// --- Camera Stream Simulators ---
async function startCamera(type) {
  const videoId = type === "checkin" ? "checkin-video" : "checkout-video";
  const overlayId = type === "checkin" ? "checkin-video-overlay" : "checkout-video-overlay";
  const video = document.getElementById(videoId);
  const overlay = document.getElementById(overlayId);

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
    video.srcObject = stream;
    if (type === "checkin") checkinStream = stream;
    else checkoutStream = stream;
    overlay.style.display = "none";
    showToast(`${type === "checkin" ? "반입" : "반출"} 카메라를 시작합니다.`, "info");
  } catch (err) {
    console.warn("Real camera access failed/declined. Using simulator visual effects.", err);
    // Show mock scanning animation
    overlay.innerHTML = `
      <div class="simulator-lens">📷</div>
      <span class="overlay-text font-semibold">카메라 시뮬레이터 동작 중</span>
      <span class="overlay-subtext">실제 카메라 권한이 차단되어 그래픽으로 대체됩니다.</span>
    `;
    overlay.style.background = "rgba(10, 8, 26, 0.4)";
  }
}

function stopCamera(type) {
  const stream = type === "checkin" ? checkinStream : checkoutStream;
  const videoId = type === "checkin" ? "checkin-video" : "checkout-video";
  const overlayId = type === "checkin" ? "checkin-video-overlay" : "checkout-video-overlay";
  const video = document.getElementById(videoId);
  const overlay = document.getElementById(overlayId);

  if (stream) {
    stream.getTracks().forEach(track => track.stop());
    if (type === "checkin") checkinStream = null;
    else checkoutStream = null;
  }
  
  if (video) video.srcObject = null;
  if (overlay) {
    overlay.style.display = "flex";
    overlay.innerHTML = `<span class="overlay-text">카메라 스캔 대기 중...</span>`;
  }
}

document.getElementById("btn-init-checkin-cam").addEventListener("click", () => {
  if (checkinStream) {
    stopCamera("checkin");
  } else {
    startCamera("checkin");
  }
});

document.getElementById("btn-init-checkout-cam").addEventListener("click", () => {
  if (checkoutStream) {
    stopCamera("checkout");
  } else {
    startCamera("checkout");
  }
});

// --- REST API Calls ---

// 1. User Registration
async function handleUserRegistration(event) {
  event.preventDefault();
  const usernameInput = document.getElementById("reg-username");
  const passwordInput = document.getElementById("reg-password");
  
  try {
    const res = await fetch("/api/users/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: usernameInput.value,
        password: passwordInput.value
      })
    });
    
    if (res.ok) {
      showToast(`계정 '${usernameInput.value}' 등록 완료!`, "success");
      usernameInput.value = "";
      passwordInput.value = "";
      loadUsers();
    } else {
      const err = await res.json();
      showToast(err.detail || "계정 생성에 실패했습니다.", "error");
    }
  } catch (err) {
    console.error(err);
    showToast("서버 네트워크 에러가 발생했습니다.", "error");
  }
}

async function loadUsers() {
  try {
    const res = await fetch("/api/users");
    if (res.ok) {
      registeredUsers = await res.json();
      renderUsers();
      if (registeredUsers.length > 0) {
        // Set last registered user as active demo user
        activeUserName.textContent = registeredUsers[registeredUsers.length - 1].username + " (연구원)";
      } else {
        activeUserName.textContent = "Guest Mode";
      }
    }
  } catch (err) {
    console.error(err);
  }
}

function renderUsers() {
  registeredUsersList.innerHTML = "";
  if (registeredUsers.length === 0) {
    registeredUsersList.innerHTML = `<li class="user-item placeholder">등록된 사용자가 없습니다.</li>`;
    return;
  }
  
  registeredUsers.forEach(u => {
    const li = document.createElement("li");
    li.className = "user-item";
    li.innerHTML = `
      <div class="user-info">
        <span class="user-name">👤 ${u.username}</span>
        <span class="user-date">등록: ${u.created_at}</span>
      </div>
      <span class="badge badge-indigo">정상</span>
    `;
    registeredUsersList.appendChild(li);
  });
}

// 2. OCR Chemical Options Loading
async function loadOcrChemicals() {
  try {
    const res = await fetch("/api/ocr-chemicals");
    if (res.ok) {
      ocrChemicals = await res.json();
    }
  } catch (err) {
    console.error(err);
  }
}

// Automated OCR Scan Simulator
window.simulateOcrScan = async function(mode, type) {
  const isCheckin = mode === "checkin";
  const videoOverlay = document.getElementById(isCheckin ? "checkin-video-overlay" : "checkout-video-overlay");
  const ocrInput = isCheckin ? checkinOcrInput : checkoutOcrInput;
  const originalOverlayHtml = videoOverlay.innerHTML;
  
  // Show "Scanning..." overlay
  videoOverlay.style.display = "flex";
  videoOverlay.innerHTML = `
    <div class="simulator-lens" style="animation: flash 0.5s infinite alternate; font-size: 36px; margin-bottom: 8px;">🔍</div>
    <span class="overlay-text font-bold" style="color: var(--accent-cyan); font-size: 14px;">OCR 분석 및 시약 식별 중...</span>
    <span class="overlay-subtext" style="color: var(--text-secondary); font-size: 11px;">카메라 프레임 내 텍스트 라벨 추출 중</span>
  `;
  
  const laser = videoOverlay.parentNode.querySelector(".scanning-laser");
  if (laser) {
    laser.style.animationDuration = "0.6s";
  }
  
  // Generate mock label text
  let text = "";
  if (type === "Random") {
    if (isCheckin) {
      const fallbackList = ocrChemicals.length > 0 ? ocrChemicals : ["Ethanol", "Acetone", "Distilled Water", "Hydrochloric Acid", "Methanol"];
      const randomChem = fallbackList[Math.floor(Math.random() * fallbackList.length)];
      text = generateMockLabel(randomChem);
    } else {
      const activeIn = activeChemicals.filter(c => c.status === "반입");
      if (activeIn.length === 0) {
        showToast("반출 가능한 시약이 현재 시약장에 없습니다.", "error");
        resetOcrOverlay(videoOverlay, originalOverlayHtml, laser);
        return;
      }
      const randomChem = activeIn[Math.floor(Math.random() * activeIn.length)].name;
      text = `[OCR REPORT] Automated Take-Out Scanning: Label matches ${randomChem} container`;
    }
  } else {
    // Specific chemical scan
    if (!isCheckin) {
      const activeIn = activeChemicals.filter(c => c.status === "반입");
      const isStored = activeIn.some(c => c.name.toLowerCase() === type.toLowerCase());
      if (!isStored) {
        showToast(`반출 불가: '${type}' 시약이 시약장에 보관되어 있지 않습니다.`, "error");
        resetOcrOverlay(videoOverlay, originalOverlayHtml, laser);
        return;
      }
    }
    text = generateMockLabel(type);
  }

  function generateMockLabel(chemName) {
    const templates = [
      `LABEL ID: ${Math.floor(Math.random() * 900000) + 100000} - Chemical: ${chemName} (Grade AR)`,
      `DANGER! ${chemName} - Keep away from heat. Volume: 1000ml`,
      `Product Sheet - Compound: ${chemName} - CAS No: Ref-99`,
      `${chemName} solution for laboratory use. Safety Goggles Required.`,
      `[BATCH #88271] - Chem Name: ${chemName} - Purity: 99.8%`
    ];
    return templates[Math.floor(Math.random() * templates.length)];
  }

  function resetOcrOverlay(overlay, originalHtml, scanLaser) {
    setTimeout(() => {
      overlay.style.display = (checkinStream && isCheckin) || (checkoutStream && !isCheckin) ? "none" : "flex";
      overlay.innerHTML = originalHtml;
      if (scanLaser) scanLaser.style.animationDuration = "2.5s";
    }, 1200);
  }

  // Simulate OCR Scan Processing Delay (1.2 seconds)
  setTimeout(async () => {
    ocrInput.value = text;
    
    try {
      const endpoint = isCheckin ? "/api/chemicals/scan-in" : "/api/chemicals/scan-out";
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ocr_text: text })
      });
      
      if (res.ok) {
        const data = await res.json();
        
        videoOverlay.style.background = isCheckin ? "rgba(16, 185, 129, 0.4)" : "rgba(244, 63, 94, 0.4)";
        videoOverlay.innerHTML = `
          <div style="font-size: 36px; margin-bottom: 8px;">✅</div>
          <span class="overlay-text font-bold" style="color: #fff;">식별 성공</span>
        `;
        
        if (isCheckin) {
          const chemicalName = data.chemical_name;
          showToast(`시약 '${chemicalName}' 식별 완료 (스캔 자동 입력)`, "success");
          if (data.has_history) {
            showToast(`이전 반입 이력이 있는 시약입니다. ESP LED를 켜서 보관 위치를 표시합니다.`, "info");
          } else {
            showToast("스캔 성공! 15초 안에 해당 시약을 선반에 올려두세요.", "info");
          }
          pollSessionState();
        } else {
          const chemicalName = data.chemical.name;
          showToast(`시약 '${chemicalName}' 반출 완료! 수납 목록에서 제거되었습니다.`, "success");
          loadChemicals();
          loadEspStatus();
        }
      } else {
        const err = await res.json();
        showToast(err.detail || "시약 스캔에 실패했습니다.", "error");
        
        videoOverlay.style.background = "rgba(239, 68, 68, 0.4)";
        videoOverlay.innerHTML = `
          <div style="font-size: 36px; margin-bottom: 8px;">❌</div>
          <span class="overlay-text font-bold" style="color: #fff;">식별 실패</span>
        `;
      }
    } catch (err) {
      console.error(err);
      showToast("서버 네트워크 에러가 발생했습니다.", "error");
    }
    
    // Reset scanner viewfinder after show results
    setTimeout(() => {
      videoOverlay.style.background = "";
      videoOverlay.style.display = (checkinStream && isCheckin) || (checkoutStream && !isCheckin) ? "none" : "flex";
      videoOverlay.innerHTML = originalOverlayHtml;
      if (laser) laser.style.animationDuration = "2.5s";
    }, 1500);
    
  }, 1200);
};

// 3. ESP & Weight Simulator
async function loadEspStatus() {
  try {
    const res = await fetch("/api/esp");
    if (res.ok) {
      espStates = await res.json();
      updateEspCards();
    }
  } catch (err) {
    console.error(err);
  }
}

function updateEspCards() {
  Object.keys(espStates).forEach(espId => {
    const board = espStates[espId];
    
    // Weight text
    document.getElementById(`weight-${espId}`).textContent = board.weight.toFixed(1);
    document.getElementById(`time-${espId}`).textContent = board.time;
    
    // LED badge/indicator class
    const ledIndicator = document.getElementById(`led-indicator-${espId}`);
    const cardElement = document.getElementById(`card-${espId}`);
    
    if (board.led_on) {
      ledIndicator.className = "esp-led-indicator led-active";
      ledIndicator.querySelector(".led-text").textContent = "LED ON: " + (board.led_message || "Active");
      cardElement.classList.add("led-active");
    } else {
      ledIndicator.className = "esp-led-indicator";
      ledIndicator.querySelector(".led-text").textContent = "LED OFF";
      cardElement.classList.remove("led-active");
    }

    // Simulator input and value updating (only if activeTab is dashboard)
    if (activeTab === "dashboard-tab") {
      const slider = document.getElementById(`sim-slider-${espId}`);
      if (slider && Math.abs(parseFloat(slider.value) - board.weight) > 0.05) {
        slider.value = board.weight;
      }
      document.getElementById(`sim-val-${espId}`).textContent = `${board.weight.toFixed(1)} kg`;
    }

    // Match shelf content (chemical placed on this shelf)
    const chemBadge = document.getElementById(`chem-badge-${espId}`);
    const activeChemOnShelf = activeChemicals.find(c => c.esp_id === espId && c.status === "반입");
    if (activeChemOnShelf) {
      chemBadge.textContent = `${activeChemOnShelf.name} (${activeChemOnShelf.weight}kg)`;
      chemBadge.className = "chemical-badge active";
    } else {
      chemBadge.textContent = "비어 있음";
      chemBadge.className = "chemical-badge";
    }
  });
}

// Simulator triggers
async function sendSimulatorWeight(espId, weight) {
  try {
    const res = await fetch(`/api/esp/${espId}/weight`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ weight: parseFloat(weight) })
    });
    
    if (res.ok) {
      const data = await res.json();
      loadEspStatus();
      loadChemicals();
      
      // If check-in is complete
      if (data.session_result && data.session_result.event === "checkin_complete") {
        showToast(`반입 성공! 시약 '${data.session_result.chemical.name}'이 ${espId} 선반에 수납되었습니다.`, "success");
      }
    }
  } catch (err) {
    console.error(err);
  }
}

// Bind Simulator Sliders
["ESP-01", "ESP-02", "ESP-03"].forEach(espId => {
  const slider = document.getElementById(`sim-slider-${espId}`);
  slider.addEventListener("input", (e) => {
    document.getElementById(`sim-val-${espId}`).textContent = `${parseFloat(e.target.value).toFixed(1)} kg`;
  });
  slider.addEventListener("change", (e) => {
    sendSimulatorWeight(espId, e.target.value);
  });
});

async function addSimWeight(espId, amount) {
  const currentWeight = espStates[espId] ? espStates[espId].weight : 0;
  const newWeight = Math.min(10.0, currentWeight + amount);
  await sendSimulatorWeight(espId, newWeight);
}

async function resetSimWeight(espId) {
  await sendSimulatorWeight(espId, 0.0);
}

async function toggleSimLed(espId) {
  const currentLed = espStates[espId] ? espStates[espId].led_on : false;
  try {
    const res = await fetch(`/api/esp/${espId}/led`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        led_on: !currentLed,
        led_message: !currentLed ? "Simulated On" : ""
      })
    });
    if (res.ok) {
      loadEspStatus();
    }
  } catch (err) {
    console.error(err);
  }
}

// 4. Chemicals & Check-in / Check-out Processes
async function loadChemicals() {
  try {
    const res = await fetch("/api/chemicals");
    if (res.ok) {
      activeChemicals = await res.json();
      renderChemicalsTable();
    }
  } catch (err) {
    console.error(err);
  }
}

function renderChemicalsTable() {
  chemicalsListBody.innerHTML = "";
  const activeInChemicals = activeChemicals.filter(c => c.status === "반입");
  chemicalCountBadge.textContent = `${activeInChemicals.length}개 수납됨`;

  if (activeInChemicals.length === 0) {
    chemicalsListBody.innerHTML = `
      <tr>
        <td colspan="6" class="table-placeholder">현재 반입 보관된 시약이 없습니다.</td>
      </tr>
    `;
    return;
  }

  // Sort: newest first
  const sorted = [...activeInChemicals].reverse();
  sorted.forEach(c => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td class="td-name font-semibold">${c.name}</td>
      <td><span class="badge badge-sw2">${c.esp_id}</span></td>
      <td class="font-mono">${c.weight.toFixed(2)} kg</td>
      <td><span class="badge badge-success">보관 중</span></td>
      <td class="time-stamp-col">${c.time_in}</td>
      <td>
        <button class="btn btn-sm btn-on" onclick="triggerLedForChemical('${c.id}')">💡 위치 표시 (LED ON)</button>
      </td>
    `;
    chemicalsListBody.appendChild(tr);
  });
}

// Scanning triggers handled by simulateOcrScan global function.

// Light LED for a specific chemical ID
async function triggerLedForChemical(chemId) {
  try {
    const res = await fetch("/api/chemicals/select-led", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ chem_id: chemId })
    });
    
    if (res.ok) {
      const data = await res.json();
      showToast(`시약 '${data.chemical_name}' 위치를 알리기 위해 ${data.esp_id} 선반 LED를 켰습니다.`, "success");
      loadEspStatus();
    } else {
      const err = await res.json();
      showToast(err.detail || "LED 조작에 실패했습니다.", "error");
    }
  } catch (err) {
    console.error(err);
  }
}

// 5. Search Chemicals & Highlight LED
btnSearchChemicals.addEventListener("click", performSearch);
searchChemInput.addEventListener("keypress", (e) => {
  if (e.key === "Enter") performSearch();
});

function performSearch() {
  const query = searchChemInput.value.toLowerCase().trim();
  const activeInChemicals = activeChemicals.filter(c => c.status === "반입");
  searchResultsList.innerHTML = "";
  
  if (activeInChemicals.length === 0) {
    searchResultsList.innerHTML = `<li class="results-placeholder">현재 반입되어 보관 중인 시약이 없습니다.</li>`;
    return;
  }
  
  const filtered = activeInChemicals.filter(c => c.name.toLowerCase().includes(query));
  
  if (filtered.length === 0) {
    searchResultsList.innerHTML = `<li class="results-placeholder">검색 결과가 없습니다.</li>`;
    return;
  }
  
  filtered.forEach(c => {
    const li = document.createElement("li");
    li.className = "result-item fade-in";
    li.innerHTML = `
      <div class="result-info">
        <span class="result-name">🧪 ${c.name}</span>
        <span class="result-location">선반: <strong>${c.esp_id}</strong> | 무게: <strong>${c.weight.toFixed(1)}kg</strong></span>
      </div>
      <button class="btn btn-sm btn-on" onclick="triggerLedForChemical('${c.id}')">💡 위치 표시 (LED ON)</button>
    `;
    searchResultsList.appendChild(li);
  });
}

// 6. Check-in Session state polling & display
async function pollSessionState() {
  try {
    const res = await fetch("/api/checkin-session");
    if (res.ok) {
      const data = await res.json();
      const statusPanel = document.getElementById("checkin-session-status");
      const statusTitle = document.getElementById("session-status-title");
      const statusMsg = document.getElementById("session-status-msg");
      const timerCircle = statusPanel.querySelector(".session-timer-circle");
      const timerVal = document.getElementById("session-timer-val");
      const progressRing = document.getElementById("timer-progress-ring");
      const recomNotice = document.getElementById("recommendation-notice");
      const recomText = document.getElementById("recommendation-text");

      if (data.active) {
        statusPanel.className = "session-status-active";
        statusTitle.textContent = "반입 대기 중 (무게 감지 대기)";
        statusMsg.textContent = `시약 '${data.chemical_name}'이(가) 스캔되었습니다. 선반에 시약을 올려 무게를 늘려주세요.`;
        
        // Show timer elements
        timerCircle.style.display = "flex";
        timerVal.textContent = Math.ceil(data.time_left);
        
        // Progress Ring Calculation (r=45 -> Circumference = 2 * Math.PI * 45 = 282.7)
        const circumference = 282.7;
        const progress = data.time_left / 15.0;
        const offset = circumference - (progress * circumference);
        progressRing.style.strokeDashoffset = offset;

        // Recommendations
        if (espStates) {
          // Check if there's recommendation
          const recommendedEsp = Object.keys(espStates).find(espId => 
            espStates[espId].led_message && espStates[espId].led_message.includes(data.chemical_name)
          );
          if (recommendedEsp) {
            recomNotice.style.display = "flex";
            recomText.innerHTML = `기존 보관 이력 선반: <strong>${recommendedEsp}</strong> (LED 켜짐)`;
          } else {
            recomNotice.style.display = "none";
          }
        }
        
        previousSessionActive = true;
      } else {
        // Inactive session
        timerCircle.style.display = "none";
        recomNotice.style.display = "none";
        statusPanel.className = "session-status-idle";
        
        if (data.timeout && previousSessionActive) {
          // If just timed out
          showToast("시간 초과! '선반에 올려놔주세요' 알림 발생", "error");
          statusTitle.textContent = "반입 시간 만료";
          statusMsg.innerHTML = `<span class="alert-danger-text">⚠️ 선반에 올려놔주세요</span>`;
          previousSessionActive = false;
        } else if (previousSessionActive) {
          // If check-in just succeeded
          statusTitle.textContent = "반입 등록 성공";
          statusMsg.textContent = "시약이 정상적으로 수납 및 등록되었습니다. 새로운 스캔을 대기합니다.";
          previousSessionActive = false;
        } else {
          // Normal idle state
          statusTitle.textContent = "반입 대기 중";
          statusMsg.textContent = "왼쪽의 스캔 카메라를 통해 시약통을 스캔해주세요.";
        }
      }
    }
  } catch (err) {
    console.error(err);
  }
}

// --- Initialization & Loop ---
function init() {
  loadUsers();
  loadOcrChemicals();
  loadEspStatus();
  loadChemicals();

  // Run status loop every 1 second
  setInterval(() => {
    loadEspStatus();
    loadChemicals();
    pollSessionState();
  }, 1000);
}

init();
