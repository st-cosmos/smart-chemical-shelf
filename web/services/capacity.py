"""시약병 용량(가득 찼을 때 총 무게, kg) 추정 — 잔량 % 의 분모.

기존에는 모든 병을 '가득 = 500g'으로 통일 가정했다. 이제 병마다 추정한다.
 1) Gemini 비전 — 라벨 사진(+OCR)으로 규격(500mL 등)과 용기 재질·형태를 읽어
    내용물+용기 총 무게를 추정한다 (기본 경로)
 2) 라벨 OCR 규격 정규식 + 밀도표 + 용기 무게 휴리스틱 (LLM 불가/실패 시)
 3) 반입 실측 무게 — 추정치보다 실측이 크면 실측으로 래칫 (routes/shelves.py)
"""
import json
import logging
import os
import re
import urllib.request
from typing import Optional

logger = logging.getLogger("capacity")

# 추정치로 인정할 총 무게 범위 (kg) — 벗어나면 오독으로 보고 버린다
MIN_CAPACITY_KG = 0.05
MAX_CAPACITY_KG = 30.0

# 대표 시약 밀도 (kg/L, 상온 근사) — 라벨이 부피(mL/L)로 표기된 경우 질량 환산용
DENSITY_KG_PER_L = {
    "에탄올": 0.79, "메탄올": 0.79, "아세톤": 0.79, "이소프로판올": 0.79,
    "톨루엔": 0.87, "헥산": 0.66, "자일렌": 0.86, "벤젠": 0.88,
    "에틸아세테이트": 0.90, "클로로포름": 1.48, "디클로로메탄": 1.33,
    "아세토니트릴": 0.78, "THF": 0.89, "DMF": 0.94, "DMSO": 1.10,
    "글리세롤": 1.26, "황산": 1.83, "질산": 1.40, "염산": 1.18,
    "과산화수소": 1.11, "암모니아": 0.90, "포름알데히드": 1.08,
    "아세트산": 1.05, "페놀": 1.07,
}

# 라벨 규격 표기: "500 mL", "1L", "500g", "2.5 kg" 등. 숫자 앞뒤로 다른 숫자·
# 단위 문자가 붙은 것(카탈로그 번호, CAS 등)은 제외한다.
CONTENT_RE = re.compile(
    r"(?<![0-9.,])(\d{1,4}(?:[.,]\d{1,2})?)\s*"
    r"(㎖|ml|㎗|l|ℓ|리터|liter|litre|㎏|kg|g|gram)(?![a-z가-힣])",
    re.IGNORECASE,
)


def _density(chemical_name: str) -> float:
    """이름으로 밀도 추정. '0.1M' 같은 몰농도 수용액은 사실상 물(1.0)로 본다."""
    name = chemical_name or ""
    if re.search(r"\d(\.\d+)?\s*M(?![a-zA-Z])", name):
        return 1.0
    for key, d in DENSITY_KG_PER_L.items():
        if key in name:
            return d
    return 1.0


def parse_label_content(ocr_text: str) -> Optional[tuple]:
    """라벨 텍스트에서 규격 표기를 찾는다. 반환: ("volume_l"|"mass_kg", 값) 또는 None.

    후보가 여럿이면 가장 큰 타당 값을 고른다 — 규격은 라벨에서 가장 큰 수치
    표기인 경우가 대부분이고, 성분 함량(예: 5 g/L)은 작은 값으로 걸러진다.
    """
    best = None
    for m in CONTENT_RE.finditer(ocr_text or ""):
        value = float(m.group(1).replace(",", "."))
        unit = m.group(2).lower()
        if unit in ("㎖", "ml"):
            kind, amount = "volume_l", value / 1000.0
        elif unit in ("㎗",):
            kind, amount = "volume_l", value / 10.0
        elif unit in ("l", "ℓ", "리터", "liter", "litre"):
            kind, amount = "volume_l", value
        elif unit in ("㎏", "kg"):
            kind, amount = "mass_kg", value
        else:  # g, gram
            kind, amount = "mass_kg", value / 1000.0
        if not (0.03 <= amount <= 20.0):
            continue
        if best is None or amount > best[1]:
            best = (kind, amount)
    return best


def _estimate_from_label(chemical_name: str, ocr_text: str) -> Optional[dict]:
    """정규식 규격 + 밀도 + 용기 무게 휴리스틱 추정 (LLM 불가 시 폴백)."""
    content = parse_label_content(ocr_text)
    if content is None:
        return None
    kind, amount = content
    if kind == "volume_l":
        net_kg = amount * _density(chemical_name)
        volume_l = amount
    else:
        net_kg = amount
        volume_l = amount  # 용기 크기 근사용 (밀도 ~1 가정)
    # 시약병은 유리 위주 — 용량에 비례하는 용기 무게 근사 (500mL 유리병 ≈ 0.2kg)
    tare_kg = min(0.05 + 0.35 * volume_l, 1.0)
    capacity = round(net_kg + tare_kg, 2)
    if not (MIN_CAPACITY_KG <= capacity <= MAX_CAPACITY_KG):
        return None
    return {"capacity_kg": capacity, "source": "label",
            "detail": f"라벨 규격 {amount}{'L' if kind == 'volume_l' else 'kg'} + 용기 추정"}


def _estimate_from_llm(chemical_name: str, ocr_text: str,
                       image_b64: Optional[str]) -> Optional[dict]:
    """Gemini 비전으로 규격·용기 재질을 읽어 가득 총 무게를 추정한다."""
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        return None

    prompt = f"""너는 실험실 시약병 전문가야. 시약 '{chemical_name}' 병의
'가득 찼을 때 총 무게(내용물 + 용기, kg)'를 추정해줘.

--- 라벨 OCR 텍스트 ---
{(ocr_text or "")[:1200]}
------------------------

추정 방법:
1. 라벨에서 규격(net content: 500mL, 1L, 500g 등)을 찾아. 사진이 있으면 사진을 우선 신뢰해.
2. 시약의 밀도로 내용물 질량을 계산해. (몰농도 수용액이면 밀도 ≈ 1.0)
3. 사진 속 용기 재질·형태(갈색 유리병/HDPE 플라스틱/캔 등)와 크기로 빈 용기 무게를 추정해.
4. full_weight_kg = 내용물 질량 + 용기 무게.

규격을 전혀 알 수 없으면 full_weight_kg 를 null 로 해. 추측으로 아무 값이나 답하지 마.

마크다운 없이 순수 JSON만 출력해:
{{"net_content": "500 mL", "container": "갈색 유리병", "full_weight_kg": 0.62}}"""

    model = os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    parts = [{"text": prompt}]
    if image_b64:
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": {"response_mime_type": "application/json", "temperature": 0.1},
    }

    try:
        req = urllib.request.Request(
            url, data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            res_json = json.loads(resp.read().decode("utf-8"))
            raw = res_json["candidates"][0]["content"]["parts"][0]["text"]
            data = json.loads(raw)
            cap = data.get("full_weight_kg") if isinstance(data, dict) else None
            if isinstance(cap, (int, float)) and MIN_CAPACITY_KG <= cap <= MAX_CAPACITY_KG:
                detail = " · ".join(
                    str(v) for v in (data.get("net_content"), data.get("container")) if v
                )
                return {"capacity_kg": round(float(cap), 2), "source": "llm",
                        "detail": detail or "LLM 추정"}
    except Exception as e:
        logger.warning(f"용량 LLM 추정 실패 ({e}) — 라벨 정규식 폴백: '{chemical_name}'")
    return None


def estimate_capacity(chemical_name: str, ocr_text: str,
                      image_b64: Optional[str] = None) -> Optional[dict]:
    """가득 총 무게 추정. LLM(비전) 우선, 실패 시 라벨 정규식 휴리스틱.

    반환: {"capacity_kg": float, "source": "llm"|"label", "detail": str} 또는 None.
    """
    return (_estimate_from_llm(chemical_name, ocr_text, image_b64)
            or _estimate_from_label(chemical_name, ocr_text))
