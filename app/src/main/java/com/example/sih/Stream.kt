package com.example.sih

import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.ws.Receive
import com.tinder.scarlet.ws.Send
import io.reactivex.Flowable

interface Stream {
    @Receive
    fun observeConnection() : Flowable<WebSocket.Event>

    @Send
    fun sendMessage(message : String)
}