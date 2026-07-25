package com.example.cameramessage

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

object AppWebSocketManager {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun connect(baseUrl: String) {
        if (webSocket != null) return

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "ws"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(listeners) {
                    listeners.forEach { it.invoke(text) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                webSocketInstance = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                webSocketInstance = null
            }
        })
    }

    private var webSocketInstance: WebSocket?
        get() = webSocket
        set(value) {
            webSocket = value
        }

    fun disconnect() {
        webSocket?.close(1000, "Activity destroyed")
        webSocket = null
    }
}
