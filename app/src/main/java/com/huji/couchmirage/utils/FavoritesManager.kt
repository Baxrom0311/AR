package com.huji.couchmirage.utils

import android.content.Context
import android.content.SharedPreferences

object FavoritesManager {
    private const val PREF_NAME = "couch_mirage_favorites"
    private const val KEY_FAVORITES = "favorite_ids"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun addFavorite(context: Context, id: String) {
        val prefs = getPrefs(context)
        val favorites = getFavorites(context).toMutableSet()
        favorites.add(id)
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun removeFavorite(context: Context, id: String) {
        val prefs = getPrefs(context)
        val favorites = getFavorites(context).toMutableSet()
        favorites.remove(id)
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    fun isFavorite(context: Context, id: String): Boolean {
        return getFavorites(context).contains(id)
    }

    fun getFavorites(context: Context): Set<String> {
        val prefs = getPrefs(context)
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }
    
    fun toggleFavorite(context: Context, id: String): Boolean {
        return if (isFavorite(context, id)) {
            removeFavorite(context, id)
            false // Removed
        } else {
            addFavorite(context, id)
            true // Added
        }
    }
}
