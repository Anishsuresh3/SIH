package com.example.sih

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class WebsocketManager : WebSocketListener() {

    private lateinit var webSocket: WebSocket
//    private var webSocketCallback: WebSocketCallback? = null
    private var messageFlow : MutableStateFlow<Any?> = MutableStateFlow(null)

    companion object {
        private var instance: WebsocketManager? = null

        fun getInstance(): WebsocketManager {
            if (instance == null) {
                instance = WebsocketManager()
            }
            return instance!!
        }
    }

    fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://demo.piesocket.com/v3/channel_123?api_key=VCXCEuvhGcBDP7XhiJJUDvR1e1D3eiVjgZ9VRiaV&notify_self")
            .build()
        webSocket = client.newWebSocket(request, this)
    }

//    fun setWebSocketCallback(callback: WebsocketManager) {
//        webSocketCallback = callback
//    }

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        // WebSocket connection is established
        println("Connected to the WebSocket server")
    }

    override fun onMessage(webSocket: WebSocket, text:String ){
//        messageFlow.value = text
//        webSocketCallback?.onDataReceived(text)
        println(text)
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        // Handle WebSocket errors
        println("WebSocket error: ${t.message}")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // Handle WebSocket connection close
        println("WebSocket connection closed with code $code: $reason")
    }

    fun sendRequest(requestMessage: String) {
        if (webSocket.send(requestMessage)) {
            // Request sent successfully
            Log.i("FUUU","sent")
        } else {
            // Failed to send request
        }
    }

    fun closeWebSocket() {
        webSocket.close(1000, "Closing WebSocket connection")
    }
}