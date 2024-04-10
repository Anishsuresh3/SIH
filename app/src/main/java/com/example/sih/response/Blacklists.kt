package com.example.sih.response

data class Blacklists(
    val detection_rate: String,
    val detections: Int,
    val engines: Engines,
    val engines_count: Int,
    val scantime: String
)