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
