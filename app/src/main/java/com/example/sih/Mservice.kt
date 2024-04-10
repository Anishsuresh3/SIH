package com.example.sih

import com.example.sih.response.Malicious
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface Mservice {
    @GET("pay-as-you-go/")
    fun getMaliciousIpReport(@Query("key") apikey: String,
                             @Query("ip") ip : String) : Call<Malicious>
    @POST("/processData")
    fun getMaliciousReport (
        @Body parameters :String
    ) : Call<MlResponse>
}
data class MlResponse(val msg : String)