package com.example.sih

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import com.tinder.scarlet.*
import com.tinder.scarlet.lifecycle.android.AndroidLifecycle
import com.tinder.scarlet.streamadapter.rxjava2.RxJava2StreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import io.reactivex.android.schedulers.AndroidSchedulers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class socketmanager(val application : Application) {
    private val ECHO_URL = "wss://socketsbay.com/wss/v2/1/demo/"
    private lateinit var webSocketService : Stream

    fun setupWebSocketService(){
        webSocketService = provideWebSocketService(
            scarlet = provideScarlet(
                client = provideOkhttp(),
                lifecycle = provideLifeCycle(),
                streamAdapter = provideStreamAdapterFactory(),
            )
        )
//        val scarlet = provideScarlet(
//            client = provideOkhttp(),
//            lifecycle = provideLifeCycle(),
//            streamAdapter = provideStreamAdapterFactory()
//        )
//        webSocketService = scarlet.create(Stream::class.java)
//        okHttpWebSocket = scarlet.(ECHO_URL).create(okhttp3.Request.Builder().url(ECHO_URL).build())
    }

    private fun provideWebSocketService(scarlet: Scarlet) = scarlet.create(Stream::class.java)

    private fun provideScarlet(
        client: OkHttpClient,
        lifecycle: Lifecycle,
        streamAdapter: StreamAdapter.Factory
    ) = Scarlet.Builder()
        .webSocketFactory(client.newWebSocketFactory(ECHO_URL))
        .lifecycle(lifecycle)
        .addStreamAdapterFactory(streamAdapter)
        .build()

    private fun provideOkhttp() =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build()

    private fun provideLifeCycle() = AndroidLifecycle.ofApplicationForeground(application)

    private fun provideStreamAdapterFactory() = RxJava2StreamAdapterFactory()

    fun sendMessage(message: String) {
        webSocketService.sendMessage(message)
    }

    @SuppressLint("CheckResult")
    fun observeConnection() {
        webSocketService.observeConnection()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                Log.i("observeConnection", response.toString())
                onReceiveResponseConnection(response)
            }, { error ->
                Log.e("observeConnection", error.message.orEmpty())
            })
    }

    private fun onReceiveResponseConnection(response: WebSocket.Event) {
        when (response) {
            is WebSocket.Event.OnConnectionOpened<*> -> {
                println("connection opened")
//                Toast.makeText(application, "connection opened", Toast.LENGTH_LONG).show()
            }
            is WebSocket.Event.OnConnectionClosed -> {
                println("connection closed")
//                Toast.makeText(application, "connection closed", Toast.LENGTH_LONG).show()
            }
            is WebSocket.Event.OnConnectionClosing -> println("closing connection..")
            is WebSocket.Event.OnConnectionFailed -> {
                println("connection failed")
//                Toast.makeText(application, "connection failed", Toast.LENGTH_LONG).show()
            }
            is WebSocket.Event.OnMessageReceived -> {
                handleOnMessageReceived(response.message)
            }
        }
    }

    private fun handleOnMessageReceived(message: Message) {
        val messageString = message.toValue()
        println("Recieved: "+messageString)
    }

    private fun Message.toValue(): String {
        return when (this) {
            is Message.Text -> value
            is Message.Bytes -> value.toString(Charsets.UTF_8)
        }
    }
    fun closeWebSocket() {
        webSocketService
    }
}