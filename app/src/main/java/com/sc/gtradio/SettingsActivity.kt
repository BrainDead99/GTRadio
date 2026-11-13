package com.sc.gtradio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.stations.SaMetadataRepository
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var isScanning = false
        private val mainHandler = Handler(Looper.getMainLooper())

        private val contract = object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                val intent = super.createIntent(context, input)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                return intent
            }
        }

        private val libraryResultLauncher = registerForActivityResult(contract) { result: Uri? ->
            if (result == null) return@registerForActivityResult
            GTRadioLogger.log("SettingsActivity: Selected library URI: $result")
            try {
                activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            val sharedPref = activity?.applicationContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            with(sharedPref?.edit()) {
                this?.putString(getString(R.string.radio_folders_uri_key), result.toString())
                this?.apply()
            }
            context?.let { DynamicThemeManager.forceRescan(it) }
        }

        private val themeResultLauncher = registerForActivityResult(contract) { result: Uri? ->
            if (result == null) return@registerForActivityResult
            GTRadioLogger.log("SettingsActivity: Selected Dynamic Theme URI: $result")
            try {
                activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            val sharedPref = activity?.applicationContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            with(sharedPref?.edit()) {
                this?.putString(getString(R.string.dynamic_theme_uri_key), result.toString())
                this?.apply()
            }
            context?.let { DynamicThemeManager.forceRescan(it) }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val pathPref = findPreference<Preference>(getString(R.string.radio_folders_uri_key))
            pathPref?.setOnPreferenceClickListener {
                libraryResultLauncher.launch(null)
                true
            }

            val themePathPref = findPreference<Preference>(getString(R.string.dynamic_theme_uri_key))
            themePathPref?.setOnPreferenceClickListener {
                themeResultLauncher.launch(null)
                true
            }

            val scanPref = findPreference<Preference>(getString(R.string.scan_album_art_key))
            scanPref?.setOnPreferenceClickListener {
                startEraArtworkScan(scanPref)
                true
            }

            val logsPref = findPreference<Preference>(getString(R.string.view_logs_key))
            logsPref?.setOnPreferenceClickListener {
                showDiagnosticLogsDialog()
                true
            }
        }

        private fun showDiagnosticLogsDialog() {
            val ctx = requireContext()
            val logText = GTRadioLogger.getLogsText()
            val tv = TextView(ctx).apply {
                text = logText
                textSize = 11f
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
            }
            val scroll = ScrollView(ctx).apply { addView(tv) }

            AlertDialog.Builder(ctx)
                .setTitle("Diagnostic Logs")
                .setView(scroll)
                .setPositiveButton("Copy Logs") { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("GTRadio Logs", logText))
                    Toast.makeText(ctx, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ ->
                    GTRadioLogger.clear(ctx)
                    Toast.makeText(ctx, "Logs cleared.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        private fun startEraArtworkScan(scanPref: Preference) {
            if (isScanning) return
            val ctx = context?.applicationContext ?: return
            isScanning = true
            scanPref.summary = "Preparing GTA San Andreas tracklist..."

            Executors.newSingleThreadExecutor().submit {
                SaMetadataRepository.ensureLoaded(ctx)
                val allSongs = ArrayList<Pair<String, String>>()
                val codes = listOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG")
                for (code in codes) {
                    val st = SaMetadataRepository.getByStreamCode(ctx, code) ?: continue
                    for (s in st.songGroups) {
                        allSongs.add(Pair(s.artist, s.cleanTitle))
                    }
                }

                var done = 0
                var okCount = 0
                val total = allSongs.size
                for ((artist, title) in allSongs) {
                    done++
                    val cur = done
                    mainHandler.post {
                        scanPref.summary = "Scanning ($cur / $total): $artist - $title"
                    }
                    if (SaMetadataRepository.fetchEraAccurateArtSync(ctx, artist, title)) {
                        okCount++
                    }
                }
                val finalOk = okCount
                mainHandler.post {
                    isScanning = false
                    scanPref.summary = "Complete! Cached $finalOk / $total era-accurate album covers offline."
                }
            }
        }
    }
}
