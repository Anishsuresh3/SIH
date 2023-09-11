package com.example.sih

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG="LocalVPN";
        private const val VPN_REQUEST_CODE = 0x0F
    }

    private lateinit var btnStart : Button
    private lateinit var btnStop : Button
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnStart.setOnClickListener {
            startVpn()
        }
        btnStop.setOnClickListener {
            stopVpn()
        }
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
            println("NULLL sdfbadf")
            startActivityForResult(intent, VPN_REQUEST_CODE);
        }else{
            println("NOT NULL sdfbadf")
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            println("ON ACtiviytyt")
            val intent = Intent(this, LocalVpnService::class.java)
            startForegroundService(intent)
        }
    }
}