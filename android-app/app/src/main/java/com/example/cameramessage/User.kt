package com.example.cameramessage

data class UserData(
    val username: String,
    val nickname: String,
    val role: String,
    val created_at: String
)

data class UserRegisterRequest(
    val username: String,
    val password: String,   // 웹 로그인용 (8자 이상, 숫자·특수문자 포함)
    val nickname: String,
    val pin: String,        // 앱 로그인용 4자리 PIN
    val role: String
)

// 안드로이드 앱 로그인: 4자리 PIN으로 인증
data class UserPinLoginRequest(
    val username: String,
    val pin: String
)
