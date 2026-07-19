package com.example.cameramessage

data class UserData(
    val username: String,
    val nickname: String,
    val role: String,
    val created_at: String
)

data class UserRegisterRequest(
    val username: String,
    val password: String,
    val nickname: String,
    val role: String
)

data class UserLoginRequest(
    val username: String,
    val password: String
)
