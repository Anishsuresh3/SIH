package com.example.sih.response

data class Malicious(
    val credits_remained: Double,
    val `data`: Data,
    val elapsed_time: String,
    val estimated_queries: String,
    val success: Boolean
)