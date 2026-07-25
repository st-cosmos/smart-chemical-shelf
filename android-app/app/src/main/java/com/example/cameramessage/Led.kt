package com.example.cameramessage

/**
 * 서버에서 받아오는 디바이스 LED 상태. (JSON의 on/time과 이름이 일치합니다.)
 */
data class DeviceState(
    val on: Boolean,
    val time: String
)

/** 서버로 보낼 명령 (켤지 on). */
data class DeviceCommand(
    val on: Boolean
)
