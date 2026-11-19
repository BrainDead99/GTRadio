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
import com.sc.gtradio.media.stations.Rpf3KeyManager
import com.sc.gtradio.media.stations.Rpf3MetadataRepository
import com.sc.gtradio.media.stations.Rpf7KeyManager
import com.sc.gtradio.media.stations.Rpf7MetadataRepository
import com.sc.gtradio.media.stations.ImportResult
import com.sc.gtradio.media.stations.Gen2MetadataRepository
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

        private fun buildKeyStatusSummary(ctx: Context, folderName: String? = null): String {
            val ivStatus = Rpf3KeyManager.getStatusLabel(ctx)
            val vStatus = if (Rpf7KeyManager.isUnlocked(ctx)) "Unlocked" else "Locked"
            val base = "GTA IV/EFLC: $ivStatus • GTA V: $vStatus\nGTRadio does not bundle game keys. Select a folder with your own GTAIV.exe, EFLC.exe, GTA5.exe, GTA5_Enhanced.exe, or .dat key files to unlock playback."
            return if (!folderName.isNullOrBlank()) {
                "$base\nFolder: $folderName"
            } else {
                base
            }
        }

        private val gameKeyResultLauncher = registerForActivityResult(contract) { result: Uri? ->
            if (result == null) return@registerForActivityResult
            GTRadioLogger.log("SettingsActivity: Selected Game Key directory URI: $result")
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            try {
                activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            val sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx)
            sharedPref.edit()
                .putString("game_key_folder_uri", result.toString())
                .putString("gtav_key_folder_uri", result.toString())
                .apply()

            val keyPref = findPreference<Preference>("game_key_folder_uri")
            keyPref?.summary = "Scanning for GTA IV / EFLC & GTA V keys..."

            Executors.newSingleThreadExecutor().submit {
                val rootDoc = DocumentFile.fromTreeUri(ctx, result)
                val foldersToScan = ArrayList<DocumentFile>()
                if (rootDoc != null && rootDoc.isDirectory) {
                    foldersToScan.add(rootDoc)
                    // Immediate subfolders (depth 1) and depth 2
                    try {
                        val subFiles = rootDoc.listFiles()
                        for (sub in subFiles) {
                            if (sub.isDirectory) {
                                foldersToScan.add(sub)
                                try {
                                    val sub2Files = sub.listFiles()
                                    for (sub2 in sub2Files) {
                                        if (sub2.isDirectory) {
                                            foldersToScan.add(sub2)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }

                var ivNewlyUnlocked = false
                var vNewlyUnlocked = false
                var vMissingMagic = false

                for (folder in foldersToScan) {
                    if (!Rpf3KeyManager.isUnlocked(ctx)) {
                        val ivRes = Rpf3KeyManager.importFromFolder(ctx, folder)
                        if (ivRes is Rpf3KeyManager.ImportResult.Success) {
                            ivNewlyUnlocked = true
                        }
                    }
                    if (!Rpf7KeyManager.isUnlocked(ctx)) {
                        val vRes = Rpf7KeyManager.importFromFolder(ctx, folder, allowMagicDownload = true)
                        if (vRes is ImportResult.Success) {
                            vNewlyUnlocked = true
                        } else if (vRes is ImportResult.MissingMagicDat) {
                            vMissingMagic = true
                        }
                    }
                    if (Rpf3KeyManager.isUnlocked(ctx) && Rpf7KeyManager.isUnlocked(ctx)) {
                        break
                    }
                }

                mainHandler.post {
                    val folderName = rootDoc?.name ?: result.lastPathSegment
                    keyPref?.summary = buildKeyStatusSummary(ctx, folderName)

                    val ivStatus = Rpf3KeyManager.getStatusLabel(ctx)
                    val vStatus = if (Rpf7KeyManager.isUnlocked(ctx)) "Unlocked" else "Locked"

                    val toastMsg = when {
                        ivNewlyUnlocked && vNewlyUnlocked -> "Keys Unlocked: GTA IV/EFLC and GTA V!"
                        ivNewlyUnlocked -> "GTA IV/EFLC Key Unlocked! (GTA V: $vStatus)"
                        vNewlyUnlocked -> "GTA V Keys Unlocked! (GTA IV/EFLC: $ivStatus)"
                        Rpf3KeyManager.isUnlocked(ctx) && Rpf7KeyManager.isUnlocked(ctx) -> "All game keys already unlocked! (GTA IV/EFLC: Unlocked, GTA V: Unlocked)"
                        vMissingMagic -> "GTA5.exe / GTA5_Enhanced.exe detected, but magic.dat could not be downloaded (offline?). Check internet connection. (GTA IV/EFLC: $ivStatus)"
                        else -> "No game keys found in selected folder. (GTA IV/EFLC: $ivStatus • GTA V: $vStatus)"
                    }
                    Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()

                    val now = System.currentTimeMillis()
                    sharedPref.edit()
                        .putLong("game_keys_updated", now)
                        .putLong("gtav_keys_updated", now)
                        .apply()
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

            val gameKeyPref = findPreference<Preference>("game_key_folder_uri")
            gameKeyPref?.let { pref ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val savedUri = prefs.getString("game_key_folder_uri", null)
                    ?: prefs.getString("gtav_key_folder_uri", null)
                val folderName = savedUri?.let { Uri.parse(it).lastPathSegment }
                pref.summary = buildKeyStatusSummary(requireContext(), folderName)
                pref.setOnPreferenceClickListener {
                    gameKeyResultLauncher.launch(null)
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

            val verbosePref = findPreference<Preference>(getString(R.string.verbose_logging_key))
            verbosePref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                GTRadioLogger.setVerboseEnabled(enabled)
                GTRadioLogger.syncMainThreadWatchdog()
                if (enabled) {
                    GTRadioLogger.checkHistoricalExitReasons(requireContext())
                }
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
            var reportText = GTRadioLogger.buildDiagnosticReport(ctx)
            val tv = TextView(ctx).apply {
                text = reportText
                textSize = 11f
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
            }
            val scroll = ScrollView(ctx).apply { addView(tv) }

            AlertDialog.Builder(ctx)
                .setTitle("Diagnostic Logs")
                .setView(scroll)
                .setPositiveButton("Copy Report") { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("GTRadio Diagnostic Report", reportText))
                    Toast.makeText(ctx, "Diagnostic report copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear Logs") { _, _ ->
                    GTRadioLogger.clearLogs(ctx)
                    reportText = GTRadioLogger.buildDiagnosticReport(ctx)
                    tv.text = reportText
                    Toast.makeText(ctx, "Logs cleared.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        private fun startEraArtworkScan(scanPref: Preference) {
            if (isScanning) return
            val ctx = context?.applicationContext ?: return
            isScanning = true
            scanPref.summary = "Preparing tracklists for album art scan..."

            Executors.newSingleThreadExecutor().submit {
                Gen2MetadataRepository.ensureLoaded(ctx)
                Rpf3MetadataRepository.ensureLoaded(ctx)

                // Tuple: (artist, title, maxYear)
                val allSongs = ArrayList<Triple<String, String, Int>>()
                val codes = listOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG")
                for (code in codes) {
                    val st = Gen2MetadataRepository.getByStreamCode(ctx, code) ?: continue
                    for (s in st.songGroups) {
                        allSongs.add(Triple(s.artist, s.cleanTitle, 2004))
                    }
                }
                for ((artist, title) in Rpf3MetadataRepository.getAllMusicSongs(ctx)) {
                    allSongs.add(Triple(artist, title, 2008))
                }
                for ((artist, title) in Rpf7MetadataRepository.getAllMusicSongs()) {
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
                        Rpf7MetadataRepository.fetchEraAccurateArtSync(ctx, artist, title, 2013)
                    } else {
                        Gen2MetadataRepository.fetchEraAccurateArtSync(ctx, artist, title, maxYear)
                    }
                    if (fetched) {
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
