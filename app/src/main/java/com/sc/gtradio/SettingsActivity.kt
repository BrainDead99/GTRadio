package com.sc.gtradio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.rpf3.Rpf3KeyManager
import com.sc.gtradio.media.rpf3.Rpf3MetadataRepository
import com.sc.gtradio.media.rpf7.Rpf7KeyManager
import com.sc.gtradio.media.rpf7.Rpf7MetadataRepository
import com.sc.gtradio.media.rpf7.ImportResult
import com.sc.gtradio.media.artwork.AlbumArtDownloader
import com.sc.gtradio.media.gen2.Gen2MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.settings_activity)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

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
            val ctx = context?.applicationContext
            if (ctx != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    DynamicThemeManager.forceRescan(ctx)
                    withContext(Dispatchers.Main.immediate) {
                        val pathPref = findPreference<Preference>(getString(R.string.radio_folders_uri_key))
                        pathPref?.summary = result.toString()
                    }
                }
            }
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
            val ctx = context?.applicationContext
            if (ctx != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    DynamicThemeManager.forceRescan(ctx)
                    withContext(Dispatchers.Main.immediate) {
                        val themePathPref = findPreference<Preference>(getString(R.string.dynamic_theme_uri_key))
                        themePathPref?.summary = result.toString()
                    }
                }
            }
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

            lifecycleScope.launch(Dispatchers.IO) {
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

                withContext(Dispatchers.Main.immediate) {
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
                if (AlbumArtDownloader.isBulkScanRunning() || isScanning) {
                    AlbumArtDownloader.cancelBulkScan()
                    isScanning = false
                    scanPref.title = getString(R.string.scan_album_art_title)
                    scanPref.summary = "Scan cancelled."
                    Toast.makeText(requireContext(), "Album art scan cancelled.", Toast.LENGTH_SHORT).show()
                } else {
                    startEraArtworkScan(scanPref)
                }
                true
            }

            val clearArtPref = findPreference<Preference>("clear_album_art_cache")
            clearArtPref?.let { pref ->
                updateClearArtSummary(pref)
                pref.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val (count, _) = AlbumArtDownloader.getAlbumArtCacheStats(ctx)
                    if (count == 0) {
                        Toast.makeText(ctx, "Album art cache is already empty.", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.clear_album_art_title)
                            .setMessage("Delete all cached album covers and album titles? You can re-download them anytime via Scan & Download All Album Art.")
                            .setPositiveButton("Delete") { _, _ ->
                                AlbumArtDownloader.cancelBulkScan()
                                isScanning = false
                                val sPref = findPreference<Preference>(getString(R.string.scan_album_art_key))
                                sPref?.title = getString(R.string.scan_album_art_title)
                                sPref?.summary = "Scan cancelled due to cache clear."
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val (_, delBytes) = AlbumArtDownloader.clearAlbumArtCache(ctx)
                                    val freedMb = String.format(Locale.US, "%.2f MB", delBytes / (1024.0 * 1024.0))
                                    withContext(Dispatchers.Main.immediate) {
                                        updateClearArtSummary(pref)
                                        Toast.makeText(ctx, "Album art cache cleared ($freedMb freed).", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    true
                }
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

        override fun onResume() {
            super.onResume()
            val clearArtPref = findPreference<Preference>("clear_album_art_cache")
            if (clearArtPref != null) {
                updateClearArtSummary(clearArtPref)
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

        private fun updateClearArtSummary(pref: Preference, ctxOverride: Context? = null) {
            val ctx = ctxOverride ?: context ?: return
            val (count, bytes) = AlbumArtDownloader.getAlbumArtCacheStats(ctx)
            if (count == 0) {
                pref.summary = "Cache is empty (0.00 MB)."
            } else {
                val sizeStr = String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
                pref.summary = "$count file(s) • $sizeStr cached offline. Tap to delete."
            }
        }

        private fun startEraArtworkScan(scanPref: Preference) {
            if (isScanning || AlbumArtDownloader.isBulkScanRunning()) return
            val ctx = context?.applicationContext ?: return
            isScanning = true
            AlbumArtDownloader.setBulkScanRunning(true)
            scanPref.title = "Cancel Album Art Scan"
            scanPref.summary = "Preparing tracklists... tap to cancel"

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Gen2MetadataRepository.ensureLoaded(ctx)
                    Rpf3MetadataRepository.ensureLoaded(ctx)
                    Rpf7MetadataRepository.ensureLoaded(ctx)

                    // Tuple: (artist, title, preferredYear, maxYear)
                    val allSongs = ArrayList<ScanTrackItem>()
                    val codes = listOf("CH", "CO", "CR", "DS", "HC", "MH", "MR", "NJ", "RE", "RG")
                    for (code in codes) {
                        if (AlbumArtDownloader.isBulkScanCancelled()) break
                        val st = Gen2MetadataRepository.getByStreamCode(ctx, code) ?: continue
                        for (s in st.songGroups) {
                            allSongs.add(ScanTrackItem(s.artist, s.cleanTitle, 1993, 2004))
                        }
                    }
                    if (!AlbumArtDownloader.isBulkScanCancelled()) {
                        for ((artist, title) in Rpf3MetadataRepository.getAllMusicSongs(ctx)) {
                            allSongs.add(ScanTrackItem(artist, title, 2009, 2009))
                        }
                    }
                    if (!AlbumArtDownloader.isBulkScanCancelled()) {
                        for ((artist, title) in Rpf7MetadataRepository.getAllMusicSongs()) {
                            allSongs.add(ScanTrackItem(artist, title, 2013, 2021))
                        }
                    }

                    val total = allSongs.size
                    GTRadioLogger.log("AlbumArtScan: Starting bulk scan for $total tracks across Gen2, RPF3, and RPF7...")

                    var done = 0
                    var okCount = 0
                    var cancelled = false
                    for (item in allSongs) {
                        if (AlbumArtDownloader.isBulkScanCancelled()) {
                            cancelled = true
                            GTRadioLogger.log("AlbumArtScan: Bulk scan cancelled by user at track $done / $total.")
                            break
                        }
                        done++
                        val cur = done
                        withContext(Dispatchers.Main.immediate) {
                            if (isScanning) {
                                scanPref.summary = "Scanning ($cur / $total): ${item.artist} - ${item.title} (tap to cancel)"
                            }
                        }
                        val fetched = AlbumArtDownloader.fetchEraAccurateArtSync(
                            context = ctx,
                            artist = item.artist,
                            songTitle = item.title,
                            preferredYear = item.preferredYear,
                            maxYear = item.maxYear
                        )
                        if (fetched) {
                            okCount++
                        }
                    }
                    val finalOk = okCount
                    val wasCancelled = cancelled || AlbumArtDownloader.isBulkScanCancelled()
                    withContext(Dispatchers.Main.immediate) {
                        isScanning = false
                        AlbumArtDownloader.setBulkScanRunning(false)
                        scanPref.title = getString(R.string.scan_album_art_title)
                        if (wasCancelled) {
                            scanPref.summary = "Scan cancelled. Cached $finalOk album covers offline."
                        } else {
                            scanPref.summary = "Complete! Cached $finalOk / $total era-accurate album covers offline."
                        }
                        val clearArtPref = findPreference<Preference>("clear_album_art_cache")
                        if (clearArtPref != null) {
                            updateClearArtSummary(clearArtPref, ctx)
                        }
                    }
                } catch (e: Exception) {
                    GTRadioLogger.log("AlbumArtScan: Error during bulk scan", e)
                    withContext(Dispatchers.Main.immediate) {
                        isScanning = false
                        AlbumArtDownloader.setBulkScanRunning(false)
                        scanPref.title = getString(R.string.scan_album_art_title)
                        scanPref.summary = "Scan failed or interrupted."
                    }
                }
            }
        }
    }
}

private data class ScanTrackItem(
    val artist: String,
    val title: String,
    val preferredYear: Int,
    val maxYear: Int
)
