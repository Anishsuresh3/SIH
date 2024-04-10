package com.example.sih.response

data class Information(
    val asn: String,
    val city_name: String,
    val continent_code: String,
    val continent_name: String,
    val country_calling_code: String,
    val country_code: String,
    val country_currency: String,
    val country_name: String,
    val isp: String,
    val latitude: Double,
    val longitude: Double,
    val region_name: String,
    val reverse_dns: String
)