package com.example.sih.response

data class Report(
    val anonymity: Anonymity,
    val blacklists: Blacklists,
    val information: Information,
    val ip: String,
    val risk_score: RiskScore
)