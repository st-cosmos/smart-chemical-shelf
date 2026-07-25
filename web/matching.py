"""OCR/바코드 → 시약 표준명 매칭 엔진.

우선순위: 바코드(학습된 것) > CAS 번호 > 별칭 정확 일치 > 별칭 부분 일치 > 유사도(fuzzy).
결과는 자동 확정(matched) / 사용자 확인 필요(needs_confirmation) / 실패(no_match)
3단계로 나뉘며, 확신이 없을 때는 앱이 사용자에게 되묻도록 needs_confirmation 을 반환한다.
사용자가 확인/직접 선택한 결과는 confirm() 으로 별칭·바코드 테이블에 학습된다.
"""
import re
from difflib import SequenceMatcher
from typing import Optional

from sqlalchemy.orm import Session

import models

# 이 점수 이상이면 확인 없이 자동 확정
AUTO_ACCEPT = 0.9
# 이 점수 이상이면 후보로 제시하고 사용자 확인을 받음 (미만이면 no_match)
CONFIRM_MIN = 0.55
# fuzzy 후보로 인정할 최소 유사도
FUZZY_MIN = 0.62

# 별칭 테이블이 비어 있을 때 시드로 넣는 기본 별칭 (구 CHEMICAL_NAME_MAPPING 대체)
DEFAULT_ALIASES = {
    "ethanol": "에탄올 95%",
    "에탄올": "에탄올 95%",
    "acetone": "아세톤",
    "아세톤": "아세톤",
    "cyanide": "시안화칼륨",
    "potassium cyanide": "시안화칼륨",
    "시안화칼륨": "시안화칼륨",
    "silver nitrate": "질산은 표준액",
    "질산은": "질산은 표준액",
    "methanol": "메탄올",
    "메탄올": "메탄올",
    "sulfuric acid": "황산 0.1M",
    "황산": "황산 0.1M",
    "toluene": "톨루엔",
    "톨루엔": "톨루엔",
}

CAS_RE = re.compile(r"(\d{2,7})-(\d{2})-(\d)")
TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣%.]+")


def normalize(s: str) -> str:
    """소문자화 후 한글/영문/숫자만 남긴다. (공백·특수문자·%가 OCR마다 달라지는 문제 흡수)"""
    return re.sub(r"[^0-9a-z가-힣]", "", (s or "").lower())


def tokenize(text: str) -> list:
    return TOKEN_RE.findall(text or "")


def cas_checksum_ok(cas: str) -> bool:
    """CAS 번호 마지막 자리는 체크디짓 — OCR 오인식 검증에 사용."""
    digits = cas.replace("-", "")
    if len(digits) < 5:
        return False
    body, check = digits[:-1], int(digits[-1])
    total = sum(int(d) * (i + 1) for i, d in enumerate(reversed(body)))
    return total % 10 == check


def extract_cas_numbers(text: str) -> list:
    return [m.group(0) for m in CAS_RE.finditer(text or "") if cas_checksum_ok(m.group(0))]


def _load_aliases(db: Session) -> list:
    """[(정규화 별칭, 표준명)] — 긴 별칭 우선(부분 일치 오탐 최소화)."""
    rows = db.query(models.ChemicalAlias).all()
    pairs = [(r.alias, r.standard_name) for r in rows if r.alias]
    pairs.sort(key=lambda p: len(p[0]), reverse=True)
    return pairs


def match(db: Session, ocr_text: str, barcode: Optional[str] = None) -> dict:
    """OCR 텍스트/바코드를 시약 표준명으로 매칭한다."""
    # 1. 바코드 — 학습된 매핑이 있으면 최우선 확정
    if barcode:
        row = db.query(models.BarcodeMap).filter(models.BarcodeMap.barcode == barcode).first()
        if row:
            return {
                "status": "matched", "method": "barcode",
                "chemical_name": row.chemical_name, "confidence": 1.0,
                "candidates": [{"name": row.chemical_name, "score": 1.0}],
                "matched_token": None,
            }

    # 2. CAS 번호 — 체크디짓 검증 통과분만 DB 대조
    for cas in extract_cas_numbers(ocr_text):
        chem = db.query(models.Chemical).filter(models.Chemical.cas_no == cas).first()
        if chem:
            return {
                "status": "matched", "method": "cas",
                "chemical_name": chem.name, "confidence": 0.98,
                "candidates": [{"name": chem.name, "score": 0.98}],
                "matched_token": cas,
            }

    aliases = _load_aliases(db)
    tokens = tokenize(ocr_text)
    norm_full = normalize(ocr_text)

    # 표준명별 최고 점수 집계: {std_name: (score, method, matched_token)}
    scores = {}

    def put(std, score, method, token):
        if std not in scores or score > scores[std][0]:
            scores[std] = (score, method, token)

    exact_names = set()
    for token in tokens:
        nt = normalize(token)
        if len(nt) < 2:
            continue
        for na, std in aliases:
            if nt == na:
                # 토큰이 별칭과 정확히 일치 → 자동 확정 후보
                put(std, 0.95, "alias", token)
                exact_names.add(std)
            elif na in nt:
                # 별칭이 더 긴 토큰의 일부 ("에탄올아민" 속 "에탄올") → 확인 필요
                put(std, 0.7, "alias", token)
            elif len(na) >= 3:
                ratio = SequenceMatcher(None, nt, na).ratio()
                if ratio >= FUZZY_MIN:
                    # 유사도 매칭은 자동 확정 금지 (최대 0.85)
                    put(std, round(ratio * 0.85, 3), "fuzzy", token)

    # 여러 토큰에 걸친 별칭("에탄올 95%")은 전체 정규화 문자열에서 탐색
    for na, std in aliases:
        if len(na) >= 4 and na in norm_full:
            put(std, max(scores.get(std, (0,))[0], 0.85), "alias", na)

    if not scores:
        return {
            "status": "no_match", "method": None, "chemical_name": None,
            "confidence": 0.0, "candidates": [], "matched_token": None,
        }

    ranked = sorted(scores.items(), key=lambda kv: kv[1][0], reverse=True)
    candidates = [{"name": std, "score": sc[0]} for std, sc in ranked[:3]]
    best_name, (best_score, best_method, best_token) = ranked[0]

    # 서로 다른 시약이 동시에 정확 일치하면(라벨에 두 이름) 자동 확정하지 않음
    ambiguous = len(exact_names) > 1

    if best_score >= AUTO_ACCEPT and not ambiguous:
        status = "matched"
    elif best_score >= CONFIRM_MIN:
        status = "needs_confirmation"
    else:
        return {
            "status": "no_match", "method": None, "chemical_name": None,
            "confidence": 0.0, "candidates": candidates, "matched_token": None,
        }

    return {
        "status": status, "method": best_method,
        "chemical_name": best_name, "confidence": best_score,
        "candidates": candidates, "matched_token": best_token,
    }


def confirm(db: Session, chemical_name: str, barcode: Optional[str] = None,
            matched_token: Optional[str] = None) -> dict:
    """사용자 확인/직접 선택 결과를 학습한다. 다음 스캔부터 즉시 인식된다."""
    learned = {"barcode": False, "alias": False}

    if barcode:
        row = db.query(models.BarcodeMap).filter(models.BarcodeMap.barcode == barcode).first()
        if row:
            row.chemical_name = chemical_name
        else:
            db.add(models.BarcodeMap(barcode=barcode, chemical_name=chemical_name))
        learned["barcode"] = True

    if matched_token:
        alias = normalize(matched_token)
        if len(alias) >= 2:
            row = db.query(models.ChemicalAlias).filter(models.ChemicalAlias.alias == alias).first()
            if not row:
                db.add(models.ChemicalAlias(alias=alias, standard_name=chemical_name))
                learned["alias"] = True

    db.commit()
    return learned


def known_names(db: Session) -> list:
    """직접 선택 UI 용 — 별칭 표준명 ∪ 보유 시약명."""
    names = {r.standard_name for r in db.query(models.ChemicalAlias).all()}
    names.update(c.name for c in db.query(models.Chemical).all())
    return sorted(names)


def ensure_alias_seed(db: Session):
    """별칭 테이블이 비어 있으면 기본 별칭을 시드한다. (기존 DB에도 안전)"""
    if db.query(models.ChemicalAlias).first() is not None:
        return
    for raw, std in DEFAULT_ALIASES.items():
        db.add(models.ChemicalAlias(alias=normalize(raw), standard_name=std))
    db.commit()
