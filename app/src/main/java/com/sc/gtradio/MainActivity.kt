package com.sc.gtradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.appbar.AppBarLayout
import com.sc.gtradio.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var defaultStatusBarColor: Int = 0
    private var defaultCutoutMode: Int = 0
    private var defaultWindowBackground: Drawable? = null
    private var defaultRootBackground: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                R.integer.read_storage_permission_request
            )
        }

        try {
            File("/sdcard/GTRadio/Dynamic Theme").mkdirs()
        } catch (_: Exception) {}

        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        @Suppress("DEPRECATION")
        defaultStatusBarColor = window.statusBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            defaultCutoutMode = window.attributes.layoutInDisplayCutoutMode
        }
        defaultWindowBackground = window.decorView.background
        defaultRootBackground = binding.root.background

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            insets
        }

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.FirstFragment) {
                setFullscreenThemeMode(false)
            }
        }
    }

    fun setFullscreenThemeMode(enabled: Boolean) {
        if (!::binding.isInitialized) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val contentLp = binding.contentMain.root.layoutParams as? CoordinatorLayout.LayoutParams
        if (enabled) {
            supportActionBar?.hide()
            binding.appBarLayout.visibility = View.GONE
            if (contentLp?.behavior != null) {
                contentLp.behavior = null
                binding.contentMain.root.layoutParams = contentLp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                    attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            binding.root.setBackgroundColor(Color.BLACK)
            insetsController.isAppearanceLightStatusBars = false
        } else {
            if (contentLp != null && contentLp.behavior !is AppBarLayout.ScrollingViewBehavior) {
                contentLp.behavior = AppBarLayout.ScrollingViewBehavior()
                binding.contentMain.root.layoutParams = contentLp
            }
            binding.appBarLayout.visibility = View.VISIBLE
            supportActionBar?.show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                if (attrs.layoutInDisplayCutoutMode != defaultCutoutMode) {
                    attrs.layoutInDisplayCutoutMode = defaultCutoutMode
                    window.attributes = attrs
                }
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = defaultStatusBarColor
            window.setBackgroundDrawable(defaultWindowBackground)
            binding.root.background = defaultRootBackground
            val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            insetsController.isAppearanceLightStatusBars = !isNightMode
        }
        binding.root.requestLayout()
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_tracklist -> {
                startActivity(Intent(this, TracklistActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    fun navigateToRadioList(stationGroupId: String?, groupName: String? = null) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (stationGroupId?.isNotBlank() == true) {
            navController.navigate(
                R.id.SecondFragment,
                Bundle().apply {
                    putString("stationGroupId", stationGroupId)
                    if (!groupName.isNullOrBlank()) {
                        putString("groupName", groupName)
                    }
                }
            )
        }
    }
}
