package com.example.sih

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.whileSelect
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.namednumber.IpNumber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.experimental.and

class LocalVpnService : VpnService(){

    companion object {
        private const val TAG = "LocalVpnService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val closeCh = Channel<Unit>()
    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var udpVpnService: UdpVpnService? = null
    private var tcpVpnService: TcpVpnService?=null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate() {
        super.onCreate()
        println("OnCrateeeeee")
        setupVpn()
        // Initialize all services for VPN.
        udpVpnService = UdpVpnService(this, inputCh, closeCh)
        udpVpnService!!.start()
        tcpVpnService= TcpVpnService(this,inputCh,closeCh)
        tcpVpnService!!.start()
        serviceScope.launch{
            startVpn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }

    private fun setupVpn() {
            var builder = Builder()
                .addAddress("10.0.2.2", 24)
                .addAddress("192.168.1.101",24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
            .setSession(TAG)
        vpnInterface = builder.establish()
        Log.i(TAG, "VPN interface has established")
    }

    suspend private fun startVpn() {
        coroutineScope{
            launch { vpnRunLoop() }
        }
    }

    suspend fun vpnRunLoop() {
        Log.i(TAG, "running loop")
        var alive = true

        // Receive from local and send to remote network.
        val vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor).channel
        // Receive from remote and send to local network.
        val vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor).channel

        serviceScope.launch {
                loop@ while (alive) {
                    val buffer = ByteBuffer.allocate(1024)
                    val readBytes = vpnInputStream.read(buffer)
                    if (readBytes <= 0) {
                        delay(100)
                        continue@loop
                    }
                    val ihlIndex = 4
                    val ihlValue: Int = (buffer.get(ihlIndex) and 0x0F).toInt()
                    if(ihlValue!=0) {
//                    println(readBytes)
//                    println(buffer.array()[0])
                    val packet = IpV4Packet.newPacket(buffer.array(),0, readBytes)
                        println(packet.header)
                    Log.i(TAG, "REQUEST\n${packet}")
                    when (packet.header.protocol) {
                        IpNumber.UDP -> {
                            udpVpnService!!.outputCh.send(packet)
                        }
                        IpNumber.TCP -> {
                            tcpVpnService!!.outputCh.send(packet)
                        }
                        else -> {
                            Log.w(TAG, "Unknown packet type");
                        }
                    }
                    }
                }
            }


        whileSelect {
            inputCh.onReceive { value ->
                Log.d(TAG, "RESPONSE\n${value}")
                vpnOutputStream.write(ByteBuffer.wrap(value.rawData))
                true
            }
            closeCh.onReceiveCatching {
                false
            }
        }
        vpnInputStream.close()
        vpnOutputStream.close()
        alive = false
        Log.i(TAG, "exit loop")
    }

    private fun stopVpn() {
        closeCh.close()
        vpnInterface?.close()
        udpVpnService?.stop()
        tcpVpnService?.stop()
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }
}