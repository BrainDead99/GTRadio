package com.sc.gtradio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
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

        private val permissionResultLauncher = registerForActivityResult(contract) { result: Uri? ->
            if (result == null) return@registerForActivityResult
            activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val sharedPref = activity?.applicationContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            with(sharedPref?.edit()) {
                this?.putString(getString(R.string.radio_folders_uri_key), result.toString())
                this?.apply()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val pathPref = findPreference<Preference>(getString(R.string.radio_folders_uri_key))
            pathPref?.setOnPreferenceClickListener {
                permissionResultLauncher.launch(null)
                true
            }

            val scanPref = findPreference<Preference>(getString(R.string.scan_album_art_key))
            scanPref?.setOnPreferenceClickListener {
                startEraArtworkScan(scanPref)
                true
            }
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
