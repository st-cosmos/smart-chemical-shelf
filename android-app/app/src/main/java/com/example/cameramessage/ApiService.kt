package com.example.cameramessage

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * web-development-python 강의의 FastAPI **LED 서버**와 통신하는 인터페이스.
 * (예제 2와 동일 — 웹 페이지·ESP32와 같은 서버를 공유합니다.)
 *
 *   GET  /api/led  → 현재 LED 상태 {on, by, time}
 *   PUT  /api/led/{led_id}  → 특정 LED 상태 변경 (body {on, by}), 바뀐 상태를 돌려줌
 */
interface ApiService {

    @GET("api/led")
    suspend fun getLed(): LedState

    @PUT("api/led/{led_id}")
    suspend fun setLed(
        @Path("led_id") ledId: String,
        @Body command: LedCommand
    ): LedState
}
