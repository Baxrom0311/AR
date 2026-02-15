package com.huji.couchmirage

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.huji.couchmirage.fragments.FavoritesFragment
import com.huji.couchmirage.fragments.HomeFragment
import com.huji.couchmirage.fragments.SettingsFragment

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_HOME = "main_home"
        private const val TAG_FAVORITES = "main_favorites"
        private const val TAG_SETTINGS = "main_settings"
    }

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved dark mode preference before content view
        applySavedDarkMode()

        setContentView(R.layout.activity_main_nav)

        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        bottomNav = findViewById(R.id.bottom_nav)
        
        // Set default fragment - Home
        if (savedInstanceState == null) {
            showFragment(TAG_HOME) { HomeFragment() }
            bottomNav.selectedItemId = R.id.nav_home
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(TAG_HOME) { HomeFragment() }
                    true
                }
                R.id.nav_ar -> {
                    // Open AR Selection activity
                    startActivity(Intent(this, ARSelectionActivity::class.java))
                    false // Don't select this item, return to previous
                }
                R.id.nav_favorites -> {
                    showFragment(TAG_FAVORITES) { FavoritesFragment() }
                    true
                }
                R.id.nav_settings -> {
                    showFragment(TAG_SETTINGS) { SettingsFragment() }
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(tag: String, factory: () -> Fragment) {
        val manager = supportFragmentManager
        val tx = manager.beginTransaction()

        listOf(TAG_HOME, TAG_FAVORITES, TAG_SETTINGS).forEach { knownTag ->
            manager.findFragmentByTag(knownTag)?.let { tx.hide(it) }
        }

        val fragment = manager.findFragmentByTag(tag) ?: factory().also {
            tx.add(R.id.fragment_container, it, tag)
        }

        tx.show(fragment).commit()
    }

    private fun applySavedDarkMode() {
        com.huji.couchmirage.utils.ThemeManager.applyTheme(this)
    }
}
