"""OCR 텍스트 → 시약명 LLM 폴백 식별기.

사전 매칭(matching.py)이 몇 초간 계속 실패할 때 앱이 1회 호출한다.
결과는 항상 사용자 확인(needs_confirmation)을 거치며, 사용자가 "맞아요"를
누르면 기존 /match/confirm 학습 경로로 라벨 문구가 별칭 사전에 등록되어
다음 스캔부터는 LLM 없이 즉시 인식된다.
"""
import json
import logging
import os
import urllib.request
from typing import Optional

logger = logging.getLogger("llm_match")


def identify_chemical_from_ocr(ocr_text: str, known_names: list) -> Optional[dict]:
    """OCR 텍스트에서 시약명을 식별한다. 실패/키 미설정 시 None.

    반환: {"name": 표준 시약명, "label_text": 라벨 원문 문구(별칭 학습용)}
    """
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        logger.info("GEMINI_API_KEY 미설정 — LLM 시약 식별 폴백 비활성")
        return None

    prompt = f"""너는 화학 시약 라벨 판독 전문가야.
아래는 시약병 라벨을 카메라 OCR로 읽은 텍스트야. 오인식된 글자가 섞여 있을 수 있어.

--- OCR 텍스트 ---
{ocr_text[:1500]}
------------------

이 병이 어떤 화학 시약인지 식별해줘.

규칙:
1. 아래 '연구실 보유 시약 목록'의 시약에 해당하면 반드시 목록의 표기를 그대로 name 으로 사용해.
2. 목록에 없는 시약이면 널리 쓰이는 한국어 시약명을 name 으로 사용해.
3. label_text 에는 OCR 텍스트에서 시약명을 나타내는 원문 문구를 그대로 담아줘. (별칭 학습용)
4. 시약명을 확실히 식별할 수 없으면 name 을 null 로 해. 추측으로 아무 시약이나 답하지 마.

연구실 보유 시약 목록: {json.dumps(known_names, ensure_ascii=False)}

마크다운 없이 순수 JSON만 출력해: {{"name": "...", "label_text": "..."}}"""

    # flash 계열은 기본 thinking 때문에 15~20초씩 걸린다 — 스캔 폴백에는
    # 1초대에 답하는 lite 가 적합 (실측: 3.1-flash-lite 1.0s vs 3.6-flash 21s)
    model = os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "response_mime_type": "application/json",
            "temperature": 0.1,
        },
    }

    try:
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        # 앱은 이 호출을 스캔과 병행해 백그라운드로 기다리므로 여유 있게 잡는다
        with urllib.request.urlopen(req, timeout=25) as resp:
            res_json = json.loads(resp.read().decode("utf-8"))
            raw_text = res_json["candidates"][0]["content"]["parts"][0]["text"]
            data = json.loads(raw_text)
            name = (data.get("name") or "").strip() if isinstance(data, dict) else ""
            if name:
                return {
                    "name": name,
                    "label_text": (data.get("label_text") or "").strip() or None,
                }
    except Exception as e:
        logger.warning(f"LLM 시약 식별 호출 실패: {e}")

    return None
