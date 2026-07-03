package com.example.cameramessage

/**
 * 서버에서 받아오는 LED 상태. (JSON 의 on/by/time 과 이름이 일치합니다.)
 * 예제 2와 같은 데이터 모양입니다.
 */
data class LedState(
    val on: Boolean,
    val by: String,
    val time: String
)

/** 서버로 보낼 명령 (켤지 on + 누가 by). 서버가 시각을 붙여 저장합니다. */
data class LedCommand(
    val on: Boolean,
    val by: String
)
