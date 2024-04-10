package com.example.sih

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.liveData
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.whileSelect
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.namednumber.IpNumber
import retrofit2.Call
import retrofit2.Response
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.experimental.and

class LocalVpnService : VpnService(){

    companion object {
        private const val TAG = "LocalVpnService"
        const val FOREGROUND_NOTIFICATION_ID = 12345
    }
    private val channelID = "com.example.sih.Malicious.notification"

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val closeCh = Channel<Unit>()
    private val inputCh = Channel<IpV4Packet>()

    private val ipAddressesToCheckChannel = Channel<String>()
    private val packetsToLogChannel = Channel<IpV4Packet>()

    private val connectionStats = HashMap<String, ConnectionStats>()

    private lateinit var connectionViewModel : ConnectionModel

    private var vpnInterface: ParcelFileDescriptor? = null

    private var udpVpnService: UdpVpnService? = null
    private var tcpVpnService: TcpVpnService?=null
    private lateinit var lifecycleOwner :  AppCompatActivity
    private var blacklists : List<String>? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activityClassName = intent?.getStringExtra("lifecycleOwnerReference")

        if (activityClassName != null) {
            try {
                val activityClass = Class.forName(activityClassName)
                val activity = activityClass.newInstance() as AppCompatActivity

                lifecycleOwner = activity
                connectionViewModel.isState.observe(lifecycleOwner, Observer {
                    println("ML report analysis : "+it)
                })

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
        connectionViewModel = ConnectionModel()
        udpVpnService = UdpVpnService(this, inputCh, closeCh)
        udpVpnService!!.start()
        tcpVpnService= TcpVpnService(this,inputCh,closeCh)
        tcpVpnService!!.start()
        serviceScope.launch{
            startVpn()
        }
        serviceScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                processIPAddresses()
            }
        }
        serviceScope.launch {
            logPackets()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }

    private fun setupVpn() {
            var builder = Builder()
                .addAddress("10.0.2.2",24)
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
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createForegroundNotification(): Notification {
        val channelId = channelID
        val channelName = "Sms Channel"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val notificationChannel = NotificationChannel(channelId, channelName, importance)
        val closeIntent = Intent(this, MainActivity::class.java)
        val closePendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            closeIntent,
            PendingIntent.FLAG_MUTABLE
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(notificationChannel)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setAction(Intent.ACTION_MAIN)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Malicious Detection")
            .setContentText("Malicious Activity detected!\n Press Stop to block the connection or Continue to allow")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(R.drawable.stop, "Stop", closePendingIntent)
            .addAction(R.drawable.stop, "Continue", closePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        return notificationBuilder.build()
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
                        val packet = IpV4Packet.newPacket(buffer.array(),0, readBytes)
                        Log.i(TAG, "REQUEST\n${packet}")
                        addToCheckList(packet.header.dstAddr.hostAddress)
                        addToLogList(packet)
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
//                        if(logConnection(packet,"REQUEST")){
////                            sendPacketWithCustomIP("127.0.0.1", packet.rawData)
//                            when (packet.header.protocol) {
//                                IpNumber.UDP -> {
//                                    udpVpnService!!.outputCh.send(packet)
//                                }
//                                IpNumber.TCP -> {
//                                    tcpVpnService!!.outputCh.send(packet)
//                                }
//                                else -> {
//                                    Log.w(TAG, "Unknown packet type");
//                                }
//                            }
                        }
                    }
                }
//            }


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
    private fun isAlreadyContains(ip : String):Boolean{
        return false
    }
    private suspend fun addToCheckList(ipAddress: String) {
        ipAddressesToCheckChannel.send(ipAddress)
    }
    private suspend fun addToLogList(packet:IpV4Packet){
        packetsToLogChannel.send(packet)
    }
//    if(packet.header.protocol == IpNumber.TCP){
//        val tcpPacket = packet.payload as TcpPacket
//        val sourcePort = tcpPacket.header.srcPort.valueAsInt()
//    }else{
//        val udpPacket = packet.payload as UdpPacket
//        val sourcePort = udpPacket.header.srcPort.valueAsInt()
//    }
    suspend fun logPackets(){
        val numWorkers = 2 // Adjust the number of workers as needed
        val workers = List(numWorkers) {
            serviceScope.launch {
                for (packet in packetsToLogChannel) {
                    if(packet.header.protocol == IpNumber.TCP){
                        val tcpPacket = packet.payload as TcpPacket
                        val key = generateConnectionKey(packet)
                        val stats = connectionStats[key]
                        if(stats!=null){
                            stats?.let {
                                it.NoPackets++
                                it.NoBytes += packet.header.totalLength
                                if(tcpPacket.header.fin){
                                    it.endTime = System.currentTimeMillis()
                                    println("Completed : "+stats)
//                                    var dur = it.endTime!! - it.startTime!!
//                                    dur = dur/1000
//                                    val data = listOf(it.sourcePort, it.destinationPort, 113, 5, dur.toFloat(), it.NoBytes, it.NoPackets)
//                                    val gson = Gson()
//                                    val json = gson.toJson(data)
//                                    connectionViewModel.getMlReport(json)
//                                    val retService =
//                                        RetrofitInstance.getRetrofitInstance().create(Mservice::class.java)
//                                    val responseLiveData: LiveData<Response<MlResponse>> = liveData {
//                                        val response = retService.getMaliciousReport(json).execute()
//                                        emit(response)
//                                    }
//                                    responseLiveData.observe(lifecycleOwner, Observer {
//                                        var data = it.body()
//                                        if(data!=null){
//                                            println("The Communication : "+data)
//                                        }
//                                    })
                                }
                            }
                        }
                        else{
                            connectionStats[key] = ConnectionStats(
                                packet.header.srcAddr.hostAddress,
                                packet.header.dstAddr.hostAddress,
                                tcpPacket.header.srcPort.valueAsInt(),
                                tcpPacket.header.dstPort.valueAsInt(),
                                1,
                                packet.header.totalLength.toLong(),
                                true,
                                System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
        }
        workers.forEach { it.join() }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun processIPAddresses() {
        // Create a pool of worker coroutines
        val numWorkers = 2 // Adjust the number of workers as needed
        val workers = List(numWorkers) {
            serviceScope.launch {
                for (ipAddress in ipAddressesToCheckChannel) {
                    // Check for malicious activity here (replace with your checks)
                    if(ipAddress!="8.8.8.8" && ipAddress!="35.244.137.80"){
                        val isMalicious = isBlacklisted(ipAddress)
                        if(isMalicious){
                            Log.i(TAG, "$ipAddress is malicious")
                            val notification = createForegroundNotification()
                            startForeground(Companion.FOREGROUND_NOTIFICATION_ID, notification)
                        }
                        else{
//                            connectionViewModel.getIpReport(ipAddress)
                        }
                    }
                    ipAddressesToCheckChannel

                }
            }
        }

        // Wait for all workers to complete
        workers.forEach { it.join() }
    }
    private fun generateConnectionKey(packet: IpV4Packet): String {
        return "${packet.header.srcAddr.hostAddress}-${packet.header.dstAddr.hostAddress}"
    }
    fun sendPacketWithCustomIP(destinationIP: String, packetPayload: ByteArray) {
        val datagramSocket = DatagramSocket()
        val datagramPacket = DatagramPacket(packetPayload, packetPayload.size, InetAddress.getByName(destinationIP), 80)
        datagramSocket.send(datagramPacket)
    }
    private fun stopVpn() {
        closeCh.close()
        vpnInterface?.close()
        udpVpnService?.stop()
        tcpVpnService?.stop()
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }
    fun isBlacklisted(ipToCheck: String): Boolean {
        if(ipToCheck=="8.8.8.8"){
            return true
        }
        if(blacklists==null){
            val listString = readTextFile("all_data.txt")
            blacklists = listString.split("\n")
        }
        return blacklists!!.contains(ipToCheck)
    }
    private fun readTextFile(fileName: String): String {
        try {
            val inputStream = assets.open(fileName)
            val size = inputStream.available()
            val buffer = ByteArray(size)

            inputStream.read(buffer)
            inputStream.close()

            return String(buffer, Charsets.UTF_8)
        } catch (e: IOException) {
            e.printStackTrace()
            return ""
        }
    }
//    private fun makeAPICall(ip:String){
//        val retService =
//            RetrofitInstance.getRetrofitInstance().create(Mservice::class.java)
//        val responseLiveData: LiveData<Call<Malicious>> = liveData {
//            val response = retService.getMaliciousIpReport("9825de25082acdf8d5af63533dd85ea15f4f8d6d",ip)
//            emit(response)
//        }
//        responseLiveData.observe(viewLifecycleOwner, Observer {
//            var data = it.body()
//            if(data!=null){
//                searchRes.adapter = SearchMovies(
//                    data!!.results,
//                    requireActivity(),
//                    R.id.action_navigation_dashboard_to_movieInfo
//                ) { selectedItem: Result ->
//                    MovieClicked(selectedItem)
//                }
//            }
//        })
//    }
}
data class IPAddressToCheck(val ipAddress: String)
data class ConnectionStats(
    val sourceAddress: String,
    val destinationAddress: String,
    val sourcePort: Int,
    val destinationPort: Int,
//    var inboundPackets: Int = 0,
    var NoPackets: Int = 0,
//    var inboundBytes: Long = 0L,
    var NoBytes: Long = 0L,
    var active: Boolean = true,
    var startTime: Long? = null,
    var endTime: Long? = null
)