package com.example.apkautomation.global

data class SavedContact(
    val id: Int,
    val name: String,
    val callSign: String = "",
    val addedTime: Long = System.currentTimeMillis()
)

data class WakeAlert(
    val fromId: Int,
    val callerName: String,
    val roomCode: String,
    val timestamp: Long = System.currentTimeMillis()
)
