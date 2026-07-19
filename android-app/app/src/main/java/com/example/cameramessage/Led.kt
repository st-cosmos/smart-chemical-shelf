package com.example.cameramessage

/**
 * 서버에서 받아오는 디바이스 상태. (JSON의 on/by/time과 이름이 일치합니다.)
 */
data class DeviceState(
    val on: Boolean,
    val by: String,
    val time: String
)

/** 서버로 보낼 명령 (켤지 on + 누가 by). 서버가 시각을 붙여 저장합니다. */
data class DeviceCommand(
    val on: Boolean,
    val by: String
)
