package com.example.cameramessage

data class ChemicalData(
    val id: String,
    val name: String,
    val cas_no: String?,
    val formula: String?,
    val weight: Double,
    val shelf_id: String?,
    val shelf_row: Int?,
    val shelf_col: Int?,
    val current_status: String,
    val holder_username: String?,
    val time_in: String?,
    val time_out: String?,
    val expiration_date: String?,
    val manufacturer: String?
)

data class TransactionLog(
    val id: Int,
    val chemical_id: String,
    val chemical_name: String,
    val action: String,
    val operator_name: String,
    val details: String?,
    val timestamp: String
)

data class UnscannedCheckout(
    val chemical_id: String,
    val chemical_name: String,
    val shelf_id: String,
    val shelf_desc: String
)

data class ExpiredChemical(
    val chemical_id: String,
    val chemical_name: String,
    val expiration_date: String,
    val days_over: Int,
    val warning: String? = null
)

data class CoStorageWarning(
    val chemical_1_id: String,
    val chemical_1_name: String,
    val chemical_2_id: String,
    val chemical_2_name: String,
    val shelf_desc: String,
    val message: String
)

data class ChemicalAlerts(
    val unscanned_checkouts: List<UnscannedCheckout>,
    val expired_chemicals: List<ExpiredChemical>,
    val co_storage_warnings: List<CoStorageWarning>
)

data class ScanInRequest(
    val ocr_text: String,
    val username: String,
    val chemical_name: String? = null  // 매칭/확인이 끝난 표준명 (서버 매칭 생략)
)

data class ScanInResponse(
    val status: String,
    val chemical_name: String,
    val has_history: Boolean,
    val recommended_shelf: String?,
    val recommended_shelf_desc: String,
    val timeout_seconds: Double
)

data class ScanOutRequest(
    val ocr_text: String,
    val username: String,
    val chemical_name: String? = null  // 매칭/확인이 끝난 표준명 (서버 매칭 생략)
)

// --- OCR/바코드 매칭 (POST /api/chemicals/match) ---

data class MatchRequest(
    val ocr_text: String,
    val barcode: String? = null
)

data class MatchCandidate(
    val name: String,
    val score: Double
)

data class MatchResult(
    val status: String,          // matched | needs_confirmation | no_match
    val method: String?,         // barcode | cas | alias | fuzzy
    val chemical_name: String?,
    val confidence: Double,
    val candidates: List<MatchCandidate>,
    val matched_token: String?
)

data class MatchConfirmRequest(
    val chemical_name: String,
    val barcode: String? = null,
    val matched_token: String? = null
)

data class ScanOutResponse(
    val status: String,
    val chemical: ChemicalData
)

data class SelectLedRequest(
    val chem_id: String
)

data class SelectLedResponse(
    val status: String,
    val shelf_id: String,
    val chemical_name: String,
    val parent_shelf: String,
    val row: Int,
    val col: Int
)

data class CheckinSessionState(
    val active: Boolean,
    val chemical_name: String,
    val time_left: Double,
    val timeout: Boolean,
    val username: String? = null
)
