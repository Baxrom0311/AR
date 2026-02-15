package com.huji.couchmirage.catalog

import android.os.Parcelable
import com.google.firebase.firestore.PropertyName
import kotlinx.parcelize.Parcelize

@Parcelize
data class CelestialBody(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",

    // Scientific data
    val mass: String? = null,
    val temperature: String? = null,
    val atmosphere: String? = null,
    val orbitalPeriod: Int = 0,
    val moons: Int = 0,

    // Astronomical data — @PropertyName maps Firebase "raduis" → radius
    @get:PropertyName("raduis") @set:PropertyName("raduis")
    var radius: Double = 0.0,

    // Educational data
    val discoveryDate: String? = null,
    val namedAfter: String? = null,
    val facts: List<String> = emptyList(),

    // Additional fields (synced with admin-panel)
    val parentPlanet: String? = null,
    val spectralType: String? = null,
    val luminosity: String? = null,
    val age: String? = null,

    // Media
    val images: List<String> = emptyList(),
    val modelUrl: String? = null
) : Parcelable
