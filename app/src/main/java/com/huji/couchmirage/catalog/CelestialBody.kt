package com.huji.couchmirage.catalog

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CelestialBody(
    val id: String = "",
    val name: String = "",
    val category: String = "", // planets, stars, moons
    val modelUrl: String = "",
    val images: List<String> = emptyList(),
    val arScale: Float = 1.0f,
    
    // Astronomical data (raduis - Firebase'dagi typo saqlab qolindi)
    val raduis: Double = 0.0,
    val mass: String = "",
    val orbitalPeriod: Int? = null,
    val temperature: String = "",
    val atmosphere: String = "",
    val moons: Int? = null,
    
    // Educational data
    val description: String = "",
    val facts: List<String> = emptyList(),
    val discoveryDate: String? = null,
    val namedAfter: String? = null
) : Parcelable {
    // Alias for new code readability while preserving legacy Firestore field name.
    val radius: Double
        get() = raduis
}
