package com.example.karootrailnames

data class Trail(
    val id: Long,
    val name: String,
    val nodes: List<TrailNode>,
    val difficulty: String? = null
)

data class TrailNode(
    val lat: Double,
    val lon: Double
)

data class TrailArea(
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val trails: List<Trail>,
    val downloadedAt: Long = System.currentTimeMillis()
)