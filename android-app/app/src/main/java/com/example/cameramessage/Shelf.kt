package com.example.cameramessage

data class ShelfDevice(
    val id: String,
    val name: String?,
    val parent_shelf: String?,
    val row: Int?,
    val col: Int?,
    val weight: Double,
    val prev_weight: Double,
    val battery: Int,
    val status: String,
    val led_on: Boolean,
    val led_message: String,
    val updated_time: String
)

data class ShelfConfig(
    val id: String,
    val name: String,
    val rows: Int,
    val cols: Int
)

data class ShelfRegister(
    val name: String,
    val parent_shelf: String,
    val row: Int,
    val col: Int
)

data class ShelfWeightUpdate(
    val weight: Double,
    val battery: Int? = null
)

data class ShelfLedUpdate(
    val led_on: Boolean,
    val led_message: String
)

data class CheckinCompleteChemical(
    val id: String,
    val name: String,
    val shelf_id: String?,
    val shelf_row: Int?,
    val shelf_col: Int?,
    val weight: Double
)

data class CheckinSessionResult(
    val event: String,
    val chemical: CheckinCompleteChemical
)

data class ShelfWeightResponse(
    val status: String,
    val shelf: ShelfDevice,
    val session_result: CheckinSessionResult?
)
