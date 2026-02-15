package com.huji.couchmirage.catalog

import android.util.Log
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.huji.couchmirage.utils.Constants
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FirebaseRepository {

    companion object {
        private const val TAG = "FirebaseRepository"

        @Volatile
        private var cacheConfigured = false

        val instance: FirebaseRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            FirebaseRepository()
        }
    }

    private val db = FirebaseFirestore.getInstance().also { firestore ->
        configureOfflineCache(firestore)
    }

    private fun configureOfflineCache(firestore: FirebaseFirestore) {
        if (cacheConfigured) return

        synchronized(FirebaseRepository::class.java) {
            if (cacheConfigured) return

            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder().build()
                    )
                    .build()
                firestore.firestoreSettings = settings
                Log.d(TAG, "Firestore offline persistence enabled")
            } catch (_: NoSuchMethodError) {
                // Compatibility fallback for older Firestore SDKs.
                @Suppress("DEPRECATION")
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .build()
                firestore.firestoreSettings = settings
                Log.d(TAG, "Firestore offline persistence enabled (legacy settings)")
            } catch (e: Exception) {
                // Settings can only be changed before first use; ignore if already initialized.
                Log.w(TAG, "Firestore settings already initialized, using existing settings", e)
            } finally {
                cacheConfigured = true
            }
        }
    }
    
    // Get Categories
    fun getCategories(onSuccess: (List<Category>) -> Unit, onError: (Exception) -> Unit) {
        db.collection(Constants.COLLECTION_CATEGORIES)
            .orderBy(Constants.FIELD_ORDER, Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val categories = documents.mapNotNull { doc ->
                    try {
                        // Use fromMap for robust parsing (handles String->Int for order field)
                        Category.fromMap(doc.data, doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing category: ${doc.id}", e)
                        null
                    }
                }
                Log.d(TAG, "Categories loaded: ${categories.size}")
                onSuccess(categories)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting categories", exception)
                onError(exception)
            }
    }
    
    // Get Celestial Bodies by Category
    fun getCelestialBodiesByCategory(
        categoryId: String,
        onSuccess: (List<CelestialBody>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        db.collection(Constants.COLLECTION_CELESTIAL_BODIES)
            .whereEqualTo(Constants.FIELD_CATEGORY, categoryId)
            .get()
            .addOnSuccessListener { documents ->
                val bodies = documents.mapNotNull { doc ->
                    try {
                        doc.toObject(CelestialBody::class.java).copy(id = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing celestial body: ${doc.id}", e)
                        null
                    }
                }
                Log.d(TAG, "Celestial bodies loaded for $categoryId: ${bodies.size}")
                onSuccess(bodies)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting celestial bodies for category: $categoryId", exception)
                onError(exception)
            }
    }
    
    // Get single Celestial Body by ID
    fun getCelestialBodyById(
        bodyId: String,
        onSuccess: (CelestialBody) -> Unit,
        onError: (Exception) -> Unit
    ) {
        db.collection(Constants.COLLECTION_CELESTIAL_BODIES)
            .document(bodyId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    try {
                        val body = document.toObject(CelestialBody::class.java)?.copy(id = document.id)
                        if (body != null) {
                            Log.d(TAG, "Celestial body loaded: ${body.name}")
                            onSuccess(body)
                        } else {
                            onError(Exception("Failed to parse celestial body"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing celestial body: $bodyId", e)
                        onError(e)
                    }
                } else {
                    onError(Exception("Celestial body not found: $bodyId"))
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting celestial body: $bodyId", exception)
                onError(exception)
            }
    }
    
    // Get Celestial Bodies by Type (planet, star, moon, other)
    // Converts type to category format used in Firebase: planet -> planets, star -> stars, etc.
    fun getCelestialBodiesByType(
        type: String,
        onSuccess: (List<CelestialBody>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Convert singular to plural to match Firebase category values
        val categoryValue = when(type.lowercase()) {
            Constants.TYPE_PLANET -> "planets"
            Constants.TYPE_STAR -> "stars"
            Constants.TYPE_MOON -> "moons"
            Constants.TYPE_OTHER -> "others"
            else -> type + "s" // fallback: add 's'
        }
        
        db.collection(Constants.COLLECTION_CELESTIAL_BODIES)
            .whereEqualTo(Constants.FIELD_CATEGORY, categoryValue)
            .get()
            .addOnSuccessListener { documents ->
                val bodies = documents.mapNotNull { doc ->
                    try {
                        doc.toObject(CelestialBody::class.java).copy(id = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing celestial body: ${doc.id}", e)
                        null
                    }
                }
                Log.d(TAG, "Celestial bodies loaded for type $type (category=$categoryValue): ${bodies.size}")
                onSuccess(bodies)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting celestial bodies for type: $type", exception)
                onError(exception)
            }
    }
    
    // Get ALL celestial bodies from all categories
    fun getAllCelestialBodies(onSuccess: (List<CelestialBody>) -> Unit, onError: (Exception) -> Unit) {
        db.collection(Constants.COLLECTION_CELESTIAL_BODIES)
            .get()
            .addOnSuccessListener { documents ->
                val bodies = documents.mapNotNull { doc ->
                    try {
                        doc.toObject(CelestialBody::class.java).copy(id = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing celestial body: ${doc.id}", e)
                        null
                    }
                }
                Log.d(TAG, "All celestial bodies loaded: ${bodies.size}")
                onSuccess(bodies)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error getting all celestial bodies", exception)
                onError(exception)
            }
    }

    // Get specific celestial bodies by IDs (batched by Firestore whereIn limit).
    fun getCelestialBodiesByIds(
        ids: List<String>,
        onSuccess: (List<CelestialBody>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) {
            onSuccess(emptyList())
            return
        }

        val chunks = uniqueIds.chunked(10)
        val collected = Collections.synchronizedMap(mutableMapOf<String, CelestialBody>())
        val pending = AtomicInteger(chunks.size)
        val failed = AtomicBoolean(false)

        chunks.forEach { chunk ->
            db.collection(Constants.COLLECTION_CELESTIAL_BODIES)
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .addOnSuccessListener { documents ->
                    if (failed.get()) return@addOnSuccessListener

                    documents.forEach { doc ->
                        try {
                            val body = doc.toObject(CelestialBody::class.java).copy(id = doc.id)
                            collected[doc.id] = body
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing celestial body: ${doc.id}", e)
                        }
                    }

                    if (pending.decrementAndGet() == 0 && !failed.get()) {
                        val ordered = uniqueIds.mapNotNull { collected[it] }
                        onSuccess(ordered)
                    }
                }
                .addOnFailureListener { exception ->
                    if (!failed.compareAndSet(false, true)) return@addOnFailureListener
                    Log.e(TAG, "Error getting celestial bodies by IDs", exception)
                    onError(exception)
                }
        }
    }
}
