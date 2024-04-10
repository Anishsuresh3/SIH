package com.example.sih

import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionModel : ViewModel() {
//    private var _sourceIp : MutableLiveData<List<String>>()
//    val sourceIp1: LiveData<List<String>> get() = _sourceIp
    private val sourceIp2 = mutableListOf<String>()
    private val desIp = mutableListOf<String>()
    val retService = RetrofitInstance.getRetrofitInstance().create(Mservice::class.java)
    private var _isState = MutableLiveData<Int>()
    val isState : LiveData<Int> get() = _isState

    fun getDesIpList(): List<String> {
        return desIp
    }

    fun appendDesIp(data: String) {
        desIp.add(data)
    }
    fun getSourceIpList(): List<String> {
        return sourceIp2
    }

    fun appendSourceIp(data: String) {
        sourceIp2.add(data)
    }
    fun getMlReport(query:String) = viewModelScope.launch(Dispatchers.IO) {
        val res = retService.getMaliciousReport(query).execute()
        if(res.isSuccessful){
            val body = res.body()
            println("Ml report "+body)
            _isState.postValue(body!!.msg.toInt())
        }
    }
    fun getIpReport(ip:String)= viewModelScope.launch(Dispatchers.Default) {
        val res = retService.getMaliciousIpReport("9825de25082acdf8d5af63533dd85ea15f4f8d6d",ip).execute()
        if(res.isSuccessful){
            val body = res.body()
            println("GOt it")
            if(body!!.data.report.blacklists.detections==0){
                _isState.postValue(0)
            }
            else if(body!!.data.report.blacklists.detections>0 && body!!.data.report.blacklists.detections<3){
                _isState.postValue(1)
            }
            else{
                _isState.postValue(-1)
            }
        }
    }
}