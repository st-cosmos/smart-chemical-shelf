package com.example.cameramessage

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    // Emulator loopback IP. Set this to your local server IP (e.g., http://192.168.x.x:8000/) for real devices.
    private const val BASE_URL = "http://10.98.81.70:8000/"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
