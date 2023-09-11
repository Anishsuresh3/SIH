package com.example.sih

import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.sync.Mutex
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Rfc1349Tos
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UnknownPacket
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import org.pcap4j.packet.namednumber.TcpOptionKind
import org.pcap4j.packet.namednumber.TcpPort
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.*

class TcpVpnService(
    val tunnel: VpnService,
    val inputCh: Channel<IpV4Packet>,
    val closeCh: Channel<Unit>) {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "TcpVpnService"
    }

    val outputCh = Channel<IpV4Packet>()
    private val selector = Selector.open()
    private val mux = Mutex()
    private val cache = mutableMapOf<String, Connection?>()

    @RequiresApi(Build.VERSION_CODES.N)
    fun start() {
        serviceScope.launch {
            whileSelect {
                outputCh.onReceive { value ->
                    launch { serveOutput(value) }
                    true
                }
                closeCh.onReceiveCatching {
                    false
                }
            }
        }
        serviceScope.launch { readLoop() }
        Log.i(TAG, "started service")
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
                    if (key.isValid) {
                        val conn = key.attachment() as Connection
                        val tcpBuilder = TcpPacket.Builder()
                            .srcAddr(conn.dstAddr)
                            .srcPort(conn.dstPort)
                            .dstAddr(conn.srcAddr)
                            .dstPort(conn.srcPort)
                            .paddingAtBuild(true)
                            .correctChecksumAtBuild(true)
                            .correctLengthAtBuild(true)

                        if (key.isConnectable) {
                            if (conn.channel!!.finishConnect()) {
                                it.remove()
                                conn.status = ConnectionStatus.SYN_RECEIVED
                                tcpBuilder.ack(true)
                                    .syn(true)
                                    .window(conn.tcpHeader.windowSize)
                                    .sequenceNumber(conn.tcpHeader.seqNum)
                                    .acknowledgmentNumber(conn.tcpHeader.ackNum)
                                    .options(conn.tcpHeader.options)
                                conn.tcpHeader.seqNum++
                                key.interestOps(SelectionKey.OP_READ)
                            }
                        } else if (key.isReadable) {
                            it.remove()
                            val buffer = ByteBuffer.allocate(3072)
                            try {
                                val readBytes = conn.channel!!.read(buffer)
                                if (readBytes == -1) {
                                    // End of stream, stop waiting until we push more data
                                    key.interestOps(0)
                                    conn.waitingForNetworkData = false
                                    if (conn.status == ConnectionStatus.CLOSE_WAIT) {
                                        conn.status = ConnectionStatus.LAST_ACK
                                        tcpBuilder.fin(true)
                                            .sequenceNumber(conn.tcpHeader.seqNum)
                                            .acknowledgmentNumber(conn.tcpHeader.ackNum)
                                        conn.tcpHeader.seqNum++
                                    }
                                } else if (readBytes > 0) {
                                    tcpBuilder.ack(true)
                                        .psh(true)
                                        .window(conn.tcpHeader.windowSize)
                                        .sequenceNumber(conn.tcpHeader.seqNum)
                                        .acknowledgmentNumber(conn.tcpHeader.ackNum)
                                        .payloadBuilder(
                                            UnknownPacket.Builder().rawData(buffer.array().take(readBytes).toByteArray())
                                        )
                                    conn.tcpHeader.seqNum = conn.tcpHeader.seqNum.plus(readBytes)
                                }
                            } catch (e: IOException) {
                                Log.e(TAG, "Network read error: ${conn.dstAddr}:${conn.dstPort}", e)
                                // closing a connection
                                tcpBuilder.rst(true)
                                    .sequenceNumber(conn.tcpHeader.seqNum)
                                    .acknowledgmentNumber(conn.tcpHeader.ackNum)
                                cache.remove(conn.key)
                                conn.close()
                            }
                            val ipv4Packet = IpV4Packet.Builder()
                                .version(IpVersion.IPV4)
                                .protocol(IpNumber.TCP)
                                .ttl(128.toByte())
                                .tos(IpV4Rfc1349Tos.newInstance(0))
                                .dontFragmentFlag(true)
                                .identification(conn.seqId)
                                .ihl(5)
                                //.options(conn.ipV4Header.options)
                                .srcAddr(conn.dstAddr)
                                .dstAddr(conn.srcAddr)
                                .correctChecksumAtBuild(true)
                                .correctLengthAtBuild(true)
                                .payloadBuilder(tcpBuilder)
                                .build()
                            inputCh.send(ipv4Packet)
                            conn.seqId++
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

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun serveOutput(ipV4Packet: IpV4Packet) {
        val tcpPacket = ipV4Packet.payload as TcpPacket
        val dstAddr = ipV4Packet.header.dstAddr
        val dstPort = tcpPacket.header.dstPort
        val srcAddr = ipV4Packet.header.srcAddr
        val srcPort = tcpPacket.header.srcPort
        val ipAndPort = "${dstAddr}:${dstPort}:${srcPort}"

        mux.lock()
        var conn: Connection? = null
        try {
            conn = cache.getOrPut(ipAndPort) {
                val newConn = Connection(ipAndPort, dstAddr, dstPort, srcAddr, srcPort)
                newConn.tcpHeader.ackNum = tcpPacket.header.sequenceNumber.inc()
                newConn.tcpHeader.seqNum = Random().nextInt(Short.MAX_VALUE.toInt())
                newConn.tcpHeader.windowSize = tcpPacket.header.window
                newConn.tcpHeader.options = tcpPacket.header.options.filter { it ->
                    it.kind == TcpOptionKind.SACK_PERMITTED ||
                            it.kind == TcpOptionKind.TIMESTAMPS ||
                            it.kind == TcpOptionKind.NO_OPERATION ||
                            it.kind == TcpOptionKind.MAXIMUM_SEGMENT_SIZE ||
                            it.kind == TcpOptionKind.WINDOW_SCALE
                }
                newConn
            }
        } finally {
            mux.unlock()
        }
        val tcpBuilder = TcpPacket.Builder()
            .srcAddr(conn!!.dstAddr)
            .srcPort(conn!!.dstPort)
            .dstAddr(conn!!.srcAddr)
            .dstPort(conn!!.srcPort)
            .paddingAtBuild(true)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)

        when {
            tcpPacket.header.syn -> {
                when (conn!!.status) {
                    null -> {
                        if (!conn!!.open()) {
                            cache.remove(ipAndPort, conn)
                            conn.close()
                            return
                        }

                        if (conn!!.channel!!.finishConnect()) {
                            conn!!.status = ConnectionStatus.SYN_RECEIVED
                            tcpBuilder.ack(true)
                                .syn(true)
                                .window(conn!!.tcpHeader.windowSize)
                                .sequenceNumber(conn!!.tcpHeader.seqNum)
                                .acknowledgmentNumber(conn!!.tcpHeader.ackNum)
                                .options(conn!!.tcpHeader.options)
                            conn!!.tcpHeader.seqNum = conn!!.tcpHeader.seqNum.inc()

                        } else {
                            conn!!.status = ConnectionStatus.SYN_SENT
                            selector.wakeup()
                            conn!!.selectionKey = conn!!.channel!!.register(selector, SelectionKey.OP_CONNECT, conn)
                            return
                        }
                    }
                    ConnectionStatus.SYN_SENT -> {
                        conn!!.tcpHeader.ackNum++
                        return
                    }
                    else -> {
                        //RST
                        tcpBuilder.rst(true)
                            .sequenceNumber(0)
                            .acknowledgmentNumber(conn!!.tcpHeader.ackNum.inc())
                            .correctChecksumAtBuild(true)
                            .correctLengthAtBuild(true)
                        cache.remove(ipAndPort)
                        conn!!.close()
                    }
                }
            }
            tcpPacket.header.fin -> {
                conn!!.tcpHeader.ackNum = tcpPacket.header.sequenceNumber.inc()
                conn!!.tcpHeader.seqNum = tcpPacket.header.acknowledgmentNumber
                tcpBuilder.ack(true)
                    .sequenceNumber(conn!!.tcpHeader.seqNum)
                    .acknowledgmentNumber(conn!!.tcpHeader.ackNum)
                if (conn!!.waitingForNetworkData) {
                    conn!!.status = ConnectionStatus.CLOSE_WAIT
                } else {
                    conn!!.status = ConnectionStatus.LAST_ACK
                    conn!!.tcpHeader.seqNum++
                    tcpBuilder.fin(true)
                }
            }
            tcpPacket.header.rst -> {
                cache.remove(ipAndPort)
                conn!!.close()
                return
            }
            tcpPacket.header.ack -> {
                if (conn!!.status == ConnectionStatus.SYN_RECEIVED) {
                    conn!!.status = ConnectionStatus.ESTABLISHED
                    selector.wakeup()
                    conn!!.selectionKey = conn!!.channel!!.register(selector, SelectionKey.OP_READ, conn)
                    conn!!.waitingForNetworkData = true
                } else if (conn!!.status == ConnectionStatus.LAST_ACK) {
                    cache.remove(ipAndPort)
                    conn!!.close()
                    return
                }
                if (tcpPacket.payload == null) {
                    return
                }
                conn!!.tcpHeader.windowSize = tcpPacket.header.window
                Log.d(TAG, "request payload: ${tcpPacket.payload.rawData.size} bytes\n${tcpPacket.payload.rawData}\n${String(tcpPacket.payload.rawData)}")
                if (!conn!!.waitingForNetworkData) {
                    selector.wakeup()
                    conn!!.selectionKey!!.interestOps(SelectionKey.OP_READ);
                    conn!!.waitingForNetworkData = true
                }
                // Forward to remote server
                conn!!.channel!!.write(ByteBuffer.wrap(tcpPacket.payload.rawData))
                conn!!.tcpHeader.seqNum = tcpPacket.header.acknowledgmentNumber
                conn!!.tcpHeader.ackNum = tcpPacket.header.sequenceNumber.plus(tcpPacket.payload.rawData.size)
                tcpBuilder.window(conn!!.tcpHeader.windowSize)
                    .ack(true)
                    .sequenceNumber(conn!!.tcpHeader.seqNum)
                    .acknowledgmentNumber(conn!!.tcpHeader.ackNum)
            }
        }
        conn!!.seqId = ipV4Packet.header.identification.inc()
        val ipv4Packet = IpV4Packet.Builder()
            .version(IpVersion.IPV4)
            .protocol(IpNumber.TCP)
            .ttl(128.toByte())
            .tos(ipV4Packet.header.tos)
            .dontFragmentFlag(true)
            .identification(conn!!.seqId)
            .ihl(ipV4Packet.header.ihl)
            //.options(ipV4Packet.header.options)
            .srcAddr(conn.dstAddr)
            .dstAddr(conn.srcAddr)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(tcpBuilder)
            .build()
        inputCh.send(ipv4Packet)
    }

    fun stop() {
        val it = cache.iterator()
        while (it.hasNext()) {
            it.next().value!!.close()
            it.remove()
        }
        Log.i(TAG, "stopped service")
    }

    enum class ConnectionStatus {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK
    }

    inner class Connection(
        val key: String,
        val dstAddr: Inet4Address,
        val dstPort: TcpPort,
        val srcAddr: Inet4Address,
        val srcPort: TcpPort) {

        val tcpHeader = TcpHeader()
        var status: ConnectionStatus? = null
        var waitingForNetworkData = false
        var channel: SocketChannel? = null
        var selectionKey: SelectionKey? = null
        var isOpen = false
        var seqId: Short = 0

        fun open(): Boolean {
            if (isOpen) {
                return true
            }
            channel = SocketChannel.open()
            channel!!.configureBlocking(false)
            tunnel.protect(channel!!.socket())
            try {
                channel!!.connect(InetSocketAddress(dstAddr, dstPort.valueAsInt()))
                Log.d(TAG, "A connection has established. ${dstAddr}:${dstPort}:${srcPort}")
            } catch (e: IOException) {
                Log.e(TAG, "Connection error: ${dstAddr}:${dstPort}:${srcPort}", e)
                return false
            }
            isOpen = true
            return true
        }

        fun close() {
            channel?.close()
            Log.d(TAG,"A connection is disconnected.")
        }

        inner class TcpHeader {
            var windowSize: Short = Short.MAX_VALUE
            var options: List<TcpPacket.TcpOption>? = null
            var ackNum: Int = 0
            var seqNum: Int = 0
        }
    }
}