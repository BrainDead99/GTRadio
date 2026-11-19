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
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.stations.GtaIvMetadataRepository
import com.sc.gtradio.media.stations.GtaVKeyManager
import com.sc.gtradio.media.stations.GtaVMetadataRepository
import com.sc.gtradio.media.stations.ImportResult
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

        private val gtavKeyResultLauncher = registerForActivityResult(contract) { result: Uri? ->
            if (result == null) return@registerForActivityResult
            GTRadioLogger.log("SettingsActivity: Selected GTA V Key directory URI: $result")
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            try {
                activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            val sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx)
            sharedPref.edit().putString("gtav_key_folder_uri", result.toString()).apply()

            val gtavKeyPref = findPreference<Preference>("gtav_key_folder_uri")
            gtavKeyPref?.summary = "Scanning for keys..."

            Executors.newSingleThreadExecutor().submit {
                val folderDoc = DocumentFile.fromTreeUri(ctx, result)
                val importRes = if (folderDoc != null && folderDoc.isDirectory) {
                    GtaVKeyManager.importFromFolder(ctx, folderDoc, allowMagicDownload = true)
                } else {
                    ImportResult.NoKeysFound
                }

                mainHandler.post {
                    val status = GtaVKeyManager.getKeyStatusSummary(ctx)
                    gtavKeyPref?.summary = "$status\nFolder: ${folderDoc?.name ?: result.lastPathSegment}"
                    when (importRes) {
                        is ImportResult.Success -> {
                            Toast.makeText(ctx, "GTA V Keys Unlocked! (${importRes.source})", Toast.LENGTH_LONG).show()
                        }
                        is ImportResult.MissingMagicDat -> {
                            Toast.makeText(ctx, "GTA5.exe found, but magic.dat could not be downloaded (offline?). Check internet connection.", Toast.LENGTH_LONG).show()
                        }
                        is ImportResult.Error -> {
                            Toast.makeText(ctx, "Key Import Error: ${importRes.message}", Toast.LENGTH_LONG).show()
                        }
                        ImportResult.NoKeysFound -> {
                            Toast.makeText(ctx, "No GTA V keys or GTA5.exe found in selected folder.", Toast.LENGTH_LONG).show()
                        }
                    }
                    sharedPref.edit().putLong("gtav_keys_updated", System.currentTimeMillis()).apply()
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val pathPref = findPreference<Preference>(getString(R.string.radio_folders_uri_key))
            pathPref?.setOnPreferenceClickListener {
                libraryResultLauncher.launch(null)
                true
            }

            val gtavKeyPref = findPreference<Preference>("gtav_key_folder_uri")
            gtavKeyPref?.let { pref ->
                val status = GtaVKeyManager.getKeyStatusSummary(requireContext())
                val currentUri = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("gtav_key_folder_uri", "") ?: ""
                pref.summary = if (currentUri.isNotBlank()) "$status\nFolder: ${Uri.parse(currentUri).lastPathSegment}" else status
                pref.setOnPreferenceClickListener {
                    gtavKeyResultLauncher.launch(null)
                    true
                }
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
            scanPref.summary = "Preparing GTA SA, GTA IV & GTA V tracklists..."

            Executors.newSingleThreadExecutor().submit {
                SaMetadataRepository.ensureLoaded(ctx)
                GtaIvMetadataRepository.ensureLoaded(ctx)

                // Tuple: (artist, title, maxYear)
                val allSongs = ArrayList<Triple<String, String, Int>>()
                val codes = listOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG")
                for (code in codes) {
                    val st = SaMetadataRepository.getByStreamCode(ctx, code) ?: continue
                    for (s in st.songGroups) {
                        allSongs.add(Triple(s.artist, s.cleanTitle, 2004))
                    }
                }
                for ((artist, title) in GtaIvMetadataRepository.getAllMusicSongs(ctx)) {
                    allSongs.add(Triple(artist, title, 2008))
                }
                for ((artist, title) in GtaVMetadataRepository.getAllMusicSongs()) {
                    allSongs.add(Triple(artist, title, 2013))
                }

                var done = 0
                var okCount = 0
                val total = allSongs.size
                for ((artist, title, maxYear) in allSongs) {
                    done++
                    val cur = done
                    mainHandler.post {
                        scanPref.summary = "Scanning ($cur / $total): $artist - $title"
                    }
                    val fetched = if (maxYear == 2013) {
                        GtaVMetadataRepository.fetchEraAccurateArtSync(ctx, artist, title, 2013)
                    } else {
                        SaMetadataRepository.fetchEraAccurateArtSync(ctx, artist, title, maxYear)
                    }
                    if (fetched) {
                        okCount++
                    }
                }
                val finalOk = okCount
                mainHandler.post {
                    isScanning = false
                    scanPref.summary = "Complete! Cached $finalOk / $total era-accurate GTA SA, GTA IV & GTA V album covers offline."
                }
            }
        }
    }
}
