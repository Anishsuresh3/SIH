package com.example.sih

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG="LocalVPN";
        private const val VPN_REQUEST_CODE = 0x0F
    }

    private lateinit var btnStart : ImageButton
    private lateinit var btnStop : ImageButton
    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var socketManager: socketmanager
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnStart = findViewById(R.id.startBtn)
        btnStop = findViewById(R.id.stopBtn)
        constraintLayout = findViewById(R.id.parent)

        btnStart.setOnClickListener {
            startVpn()
            constraintLayout.background = resources.getDrawable(R.drawable.card_stop)
            btnStart.visibility = View.INVISIBLE
            btnStop.visibility = View.VISIBLE
        }
        btnStop.setOnClickListener {
            stopVpn()
            constraintLayout.background = resources.getDrawable(R.drawable.card_layer)
            btnStop.visibility = View.INVISIBLE
            btnStart.visibility = View.VISIBLE
        }
//        socketManager = WebsocketManager.getInstance()
//        socketManager.connectWebSocket()
//        socketManager = socketmanager(application)
//        socketManager.setupWebSocketService()
//        socketManager.observeConnection()
//        lifecycleScope.launch {
//            delay(10000)
//            socketManager.closeWebSocket()
//            println("Stopped")
//        }
    }

    fun stopVpn(){
        Log.d(TAG,"Stop VPN")
        val intent = Intent(this, LocalVpnService::class.java)
        intent.putExtra("COMMAND", "STOP")
        startService(intent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startVpn(){
        Log.d(TAG,"Start VPN")
        val intent= VpnService.prepare(this)
        if (intent!=null){
            startActivityForResult(intent, VPN_REQUEST_CODE);
        }else{
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            println("ON ACtiviytyt")
            val intent = Intent(this, LocalVpnService::class.java)
            intent.putExtra("lifecycleOwnerReference", this::class.java.name)
            startForegroundService(intent)
        }
    }
}