package com.huji.couchmirage.catalog

data class Category(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    
    // Handle both "description" and "Description" from Firestore
    val description: String = "",
    
    val order: Int = 0
) {
    // Secondary constructor to handle order as String from Firestore
    companion object {
        fun fromMap(data: Map<String, Any?>, id: String): Category {
            return Category(
                id = id,
                name = data["name"] as? String ?: "",
                icon = data["icon"] as? String ?: "",
                description = (data["description"] ?: data["Description"]) as? String ?: "",
                order = when (val orderValue = data["order"]) {
                    is Number -> orderValue.toInt()
                    is String -> orderValue.toIntOrNull() ?: 0
                    else -> 0
                }
            )
        }
    }
}
