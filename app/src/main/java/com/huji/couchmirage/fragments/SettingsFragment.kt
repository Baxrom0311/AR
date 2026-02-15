package com.huji.couchmirage.fragments

import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.huji.couchmirage.R
import java.io.File

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val prefsName = "app_settings"
    private val keyNotifications = "notifications_enabled"
    private val keyDarkMode = "dark_mode_enabled"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSettingsItem(view.findViewById(R.id.setting_notifications), "Bildirishnomalar") {
            togglePreference(keyNotifications, "Bildirishnomalar")
        }

        setupSettingsItem(view.findViewById(R.id.setting_dark_mode), "Qorong'u rejim") {
            com.huji.couchmirage.utils.ThemeManager.toggleTheme(requireContext())
            val isDark = com.huji.couchmirage.utils.ThemeManager.isDarkMode(requireContext())
            val status = if (isDark) "yoqildi" else "o'chirildi"
            Toast.makeText(requireContext(), "Qorong'u rejim $status", Toast.LENGTH_SHORT).show()
        }

        setupSettingsItem(view.findViewById(R.id.setting_language), "Til: O'zbekcha") {
            Toast.makeText(requireContext(), "Til sozlamalari keyingi versiyada qo'shiladi", Toast.LENGTH_SHORT).show()
        }

        setupSettingsItem(view.findViewById(R.id.setting_cache), "Keshni tozalash") {
            clearCaches()
        }

        setupSettingsItem(view.findViewById(R.id.setting_about), "Ilova haqida") {
            showAboutDialog()
        }

        val versionName = getAppVersionName()
        view.findViewById<TextView>(R.id.version_text)?.text = "Versiya $versionName"
    }

    private fun setupSettingsItem(item: View?, title: String, onClick: () -> Unit) {
        item?.apply {
            findViewById<TextView>(R.id.setting_title)?.text = title
            setOnClickListener { onClick() }
        }
    }

    private fun togglePreference(key: String, title: String) {
        val prefs = requireContext().getSharedPreferences(prefsName, 0)
        val newValue = !prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, newValue).apply()

        // Logic moved to ThemeManager for Dark Mode
        // This function now only handles generic boolean prefs like notifications


        val status = if (newValue) "yoqildi" else "o'chirildi"
        Toast.makeText(requireContext(), "$title $status", Toast.LENGTH_SHORT).show()
    }

    // applySavedDarkMode removed - handled by MainActivity and ThemeManager

    private fun clearCaches() {
        val context = requireContext()
        var deletedCount = 0

        context.cacheDir?.let { cacheDir ->
            deletedCount += deleteChildren(cacheDir)
        }

        Toast.makeText(requireContext(), "Kesh tozalandi: $deletedCount fayl", Toast.LENGTH_SHORT).show()
    }

    private fun deleteChildren(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.deleteRecursively()) {
                count += 1
            }
        }
        return count
    }

    private fun showAboutDialog() {
        val versionName = getAppVersionName()
        AlertDialog.Builder(requireContext())
            .setTitle("Ilova haqida")
            .setMessage("Astronomy AR\nVersiya $versionName\n\nQuyosh tizimini ARda o'rganish uchun mo'ljallangan.")
            .setPositiveButton("Yopish", null)
            .show()
    }

    private fun getAppVersionName(): String {
        val context = requireContext()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                ).versionName ?: "Noma'lum"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Noma'lum"
            }
        } catch (e: Exception) {
            "Noma'lum"
        }
    }
}
