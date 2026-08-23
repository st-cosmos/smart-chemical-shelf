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
CONFIRM_MIN = 0.65
# fuzzy 후보로 인정할 최소 유사도
FUZZY_MIN = 0.70

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
    "sulfuric": "황산 0.1M",
    "h2so4": "황산 0.1M",
    "황산": "황산 0.1M",
    "hydrochloric acid": "염산 1M",
    "hydrochloric": "염산 1M",
    "hcl": "염산 1M",
    "염산": "염산 1M",
    "nitric acid": "질산 65%",
    "nitric": "질산 65%",
    "hno3": "질산 65%",
    "질산": "질산 65%",
    "acetic acid": "아세트산",
    "acetic": "아세트산",
    "ch3cooh": "아세트산",
    "아세트산": "아세트산",
    "초산": "아세트산",
    "빙초산": "아세트산",
    "sodium hydroxide": "수산화나트륨",
    "naoh": "수산화나트륨",
    "수산화나트륨": "수산화나트륨",
    "hydrogen peroxide": "과산화수소",
    "과산화수소": "과산화수소",
    "isopropanol": "이소프로판올",
    "ipa": "이소프로판올",
    "isopropyl alcohol": "이소프로판올",
    "이소프로필알코올": "이소프로판올",
    "2propanol": "이소프로판올",
    "이소프로판올": "이소프로판올",
    "toluene": "톨루엔",
    "톨루엔": "톨루엔",
    # 영문/한글 동의어 — methyl↔ethyl 하이재킹 방지를 위해 반드시 전체 구절로 등록
    "methyl alcohol": "메탄올",
    "메틸알코올": "메탄올",
    "ethyl alcohol": "에탄올 95%",
    "에틸알코올": "에탄올 95%",
    # 실험실 상용 시약 어휘 확장
    "benzene": "벤젠",
    "벤젠": "벤젠",
    "chloroform": "클로로포름",
    "chcl3": "클로로포름",
    "클로로포름": "클로로포름",
    "acetonitrile": "아세토니트릴",
    "acn": "아세토니트릴",
    "mecn": "아세토니트릴",
    "아세토니트릴": "아세토니트릴",
    "dichloromethane": "디클로로메탄",
    "dcm": "디클로로메탄",
    "디클로로메탄": "디클로로메탄",
    "ethyl acetate": "에틸아세테이트",
    "etoac": "에틸아세테이트",
    "에틸아세테이트": "에틸아세테이트",
    "hexane": "헥산",
    "nhexane": "헥산",
    "헥산": "헥산",
    "xylene": "자일렌",
    "자일렌": "자일렌",
    "ammonia": "암모니아수",
    "nh4oh": "암모니아수",
    "암모니아": "암모니아수",
    "암모니아수": "암모니아수",
    "potassium permanganate": "과망간산칼륨",
    "kmno4": "과망간산칼륨",
    "과망간산칼륨": "과망간산칼륨",
    "formaldehyde": "포름알데히드",
    "formalin": "포름알데히드",
    "포르말린": "포름알데히드",
    "포름알데히드": "포름알데히드",
    "glycerol": "글리세롤",
    "glycerin": "글리세롤",
    "글리세린": "글리세롤",
    "글리세롤": "글리세롤",
    "phenol": "페놀",
    "페놀": "페놀",
    "dimethyl sulfoxide": "DMSO",
    "dmso": "DMSO",
    "dimethylformamide": "DMF",
    "dmf": "DMF",
    "tetrahydrofuran": "THF",
    "thf": "THF",
}

CAS_RE = re.compile(r"(\d{2,7})-(\d{2})-(\d)")
TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣%.]+")
KOREAN_ONLY_RE = re.compile(r"^[가-힣]+$")


def _usable_short(s: str) -> bool:
    """2자여도 순수 한글이면 유효 토큰/별칭으로 인정한다.
    '황산'·'질산'·'염산' 등 2자 한글 시약명이 3자 미만 노이즈 필터에
    걸려 원천적으로 매칭 불가능해지는 문제를 막는다. (영문 2자는 여전히 제외)"""
    return len(s) >= 2 and bool(KOREAN_ONLY_RE.match(s))


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
    
    # 추가: 실제 등록된 시약 이름들도 자동 별칭으로 포함하여 OCR 인식을 지원한다.
    for chem in db.query(models.Chemical).all():
        if chem.name:
            norm = normalize(chem.name)
            if norm:
                pairs.append((norm, chem.name))
                
    pairs = list(set(pairs))
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
        if len(nt) < 3 and not _usable_short(nt):  # 짧은 노이즈 토큰 제외 (2자 한글은 허용)
            continue
        # "토큰이 별칭의 일부" 규칙용: 이 토큰을 포함하는 별칭들의 표준명 집합.
        # 여러 표준명에 걸치면("acid" ⊂ 염산·질산·황산·아세트산) 정보가 없는
        # 조각이므로 해당 규칙을 적용하지 않는다 — boric acid → 염산 오탐 방지.
        contained_stds = (
            {std for na, std in aliases if nt != na and nt in na}
            if len(nt) >= 4 else set()
        )
        for na, std in aliases:
            if nt == na:
                # 토큰이 별칭과 정확히 일치 → 자동 확정 후보
                put(std, 0.95, "alias", token)
                exact_names.add(std)
            elif nt.startswith(na):
                # 별칭이 더 긴 토큰의 접두("에탄올아민" 속 "에탄올") → 확인 필요.
                # 임의 위치 부분 일치는 금지 — "methyl" 속 "ethyl" 같은 하이재킹 방지.
                put(std, 0.7, "alias", token)
            elif len(nt) >= 4 and nt in na and len(contained_stds) == 1:
                # 토큰이 별칭의 일부분일 때 (예: "hydrochloric" -> "hydrochloric acid")
                put(std, 0.8, "alias", token)
            elif len(na) >= 3 and len(nt) >= 3:
                # 길이 차이가 너무 큰 조합의 오탐 방지 (예: 노이즈 3자 vs 별칭 8자)
                if abs(len(na) - len(nt)) <= max(len(na), len(nt)) // 2:
                    ratio = SequenceMatcher(None, nt, na).ratio()
                    if ratio >= FUZZY_MIN:
                        # 유사도 매칭은 자동 확정 금지 (최대 0.85)
                        put(std, round(ratio * 0.85, 3), "fuzzy", token)

    # 여러 토큰에 걸친 별칭("에탄올 95%", "sulfuric acid")은 전체 정규화 문자열에서 탐색
    # (2~3자 한글 별칭도 허용 — OCR이 "황 산"처럼 띄어 읽어 토큰이 깨지는 경우 대비)
    full_hits = [
        (na, std) for na, std in aliases
        if (len(na) >= 4 or _usable_short(na)) and na in norm_full
    ]
    for na, std in full_hits:
        # 더 긴 다른 매칭 별칭에 통째로 포함되는 별칭은 무시 —
        # "methylalcohol" 안의 "ethylalcohol"처럼 겹쳐 잡히는 오탐 방지
        if any(na != nb and na in nb for nb, _ in full_hits):
            continue
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
        # 짧은 영문/숫자 조각("eth", "thy" 등)은 부분 일치 오탐의 씨앗이 되므로
        # 학습하지 않는다. 한글은 2자부터, 그 외는 5자부터 별칭으로 인정.
        if _usable_short(alias) or len(alias) >= 5:
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
    """DEFAULT_ALIASES 중 DB에 없는 항목을 보충 시드한다.

    과거에는 테이블이 비어 있을 때만 시드해서, 사전에 나중에 추가된
    별칭(질산·염산 등)이 기존 DB에 영영 반영되지 않는 문제가 있었다.
    학습으로 쌓인 별칭은 건드리지 않고 누락분만 추가한다."""
    existing = {r.alias for r in db.query(models.ChemicalAlias).all()}
    added = False
    for raw, std in DEFAULT_ALIASES.items():
        alias = normalize(raw)
        if alias and alias not in existing:
            db.add(models.ChemicalAlias(alias=alias, standard_name=std))
            existing.add(alias)
            added = True
    if added:
        db.commit()
