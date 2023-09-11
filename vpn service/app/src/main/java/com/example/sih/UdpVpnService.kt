package com.example.sih

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.sync.Mutex
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Rfc1349Tos
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import org.pcap4j.packet.namednumber.UdpPort
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

class UdpVpnService(
    val tunnel: VpnService,
    val inputCh: Channel<IpV4Packet>,
    val closeCh: Channel<Unit>) {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "UdpVpnService"
    }

    val outputCh= Channel<IpV4Packet>()

    private val selector = Selector.open()
    private val mux = Mutex()
    private val cache = mutableMapOf<String, Connection?>()

    fun start() {
        serviceScope.launch {
            whileSelect {
                outputCh.onReceive { value ->
                    // received package(local >> remote)
                    launch { serveOutput(value) }
                    true
                }
                closeCh.onReceiveCatching {
                    false
                }
            }
            Log.d(TAG, "exit main loop")
        }
        serviceScope.launch { readLoop() }
        // receive data from the remote server.
        Log.i(TAG, "start service")
    }

    private suspend fun readLoop() {
        val readyCh = Channel<Int>()
        var alive = true
        serviceScope.launch {
            loop@ while (alive) {
                val n = selector.selectNow()
                if (n == 0) {
                    delay(100)
                    continue@loop
                }
                readyCh.send(n)
            }
        }

        whileSelect {
            readyCh.onReceive {
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key.isValid && key.isReadable) {
                        it.remove()
                        val channel = key.channel() as DatagramChannel
                        // TODO: should be take BufferPool.
                        val buffer = ByteBuffer.allocate(1024)
                        // Read dates.
                        val readBytes = channel.read(buffer)
                        if (readBytes > 0) {
                            val conn = key.attachment() as Connection
                            var packet = IpV4Packet.Builder()
                                .version(IpVersion.IPV4)
                                .protocol(IpNumber.UDP)
                                .ttl(64)
                                .tos(IpV4Rfc1349Tos.newInstance(0))
                                .dontFragmentFlag(true)
                                .ihl(5)
                                .identification(1)
                                .srcAddr(conn.dstAddr)
                                .dstAddr(conn.srcAddr)
                                .correctChecksumAtBuild(true)
                                .correctLengthAtBuild(true)
                                .payloadBuilder(
                                    UdpPacket.Builder()
                                        .srcPort(conn.dstPort)
                                        .dstPort(conn.srcPort)
                                        .srcAddr(conn.dstAddr)
                                        .dstAddr(conn.srcAddr)
                                        .correctChecksumAtBuild(true)
                                        .correctLengthAtBuild(true)
                                        .payloadBuilder(
                                            UnknownPacket.Builder().rawData(buffer.array().take(readBytes).toByteArray())
                                        )
                                ).build()
                            inputCh.send(packet)
                        }
                    }
                }
                true
            }
            closeCh.onReceiveCatching {
                false
            }
        }
        alive = false
    }

    private suspend fun serveOutput(ipV4Packet: IpV4Packet) {
        val udpPacket = ipV4Packet.payload as UdpPacket
        val dstAddr = ipV4Packet.header.dstAddr
        val dstPort = udpPacket.header.dstPort
        val srcAddr = ipV4Packet.header.srcAddr
        val srcPort = udpPacket.header.srcPort

        val ipAndPort = "${dstAddr}:${dstPort}:${srcPort}"
        Log.d(TAG, ipAndPort)
        var conn: Connection? = null
        mux.lock()
        try {
            conn = cache.getOrPut(ipAndPort) {
                val newconn = Connection(dstAddr, dstPort, srcAddr, srcPort)
                newconn
            }
            if (!conn!!.isOpen && !conn!!.open()) {
                conn!!.close()
                cache.remove(ipAndPort)
                return
            }
        } finally {
            mux.unlock()
        }
        val buff = ByteBuffer.wrap(udpPacket.payload.rawData)
        while (buff.hasRemaining()) {
            conn!!.channel!!.write(buff)
        }
    }

    fun stop() {
        val it = cache.iterator()
        while (it.hasNext()) {
            it.next().value!!.close()
            it.remove()
        }
        Log.i(TAG, "stop service")
    }

    inner class Connection(
        val dstAddr: Inet4Address,
        val dstPort: UdpPort,
        val srcAddr: Inet4Address,
        val srcPort: UdpPort) {

        var channel: DatagramChannel? = null
        var isOpen = false

        fun open(): Boolean {
            if (isOpen) {
                return true
            }
            channel = DatagramChannel.open()
            // protect a socket before connect to the remote server.
            tunnel.protect(channel!!.socket())
            channel!!.configureBlocking(false)
            try {
                channel!!.connect(InetSocketAddress(dstAddr, dstPort.valueAsInt()))
                Log.d(TAG, "A connection has established. ${dstAddr}:${dstPort}:${srcPort}")
            } catch (e: IOException) {
                Log.e(TAG, "Connection error: ${dstAddr}:${dstPort}:${srcPort}", e)
                return false
            }

            selector.wakeup()
            channel!!.register(selector, SelectionKey.OP_READ, this)
            isOpen = true
            return true
        }

        fun close() {
            channel?.close()
            isOpen = false
        }

        override fun toString(): String {
            return "${srcAddr}:${srcPort} , ${dstAddr}:${dstPort}"
        }
    }
}