package com.example.sih.response

data class Anonymity(
    val is_hosting: Boolean,
    val is_proxy: Boolean,
    val is_tor: Boolean,
    val is_vpn: Boolean,
    val is_webproxy: Boolean
)