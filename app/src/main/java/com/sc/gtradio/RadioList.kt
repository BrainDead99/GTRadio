package com.sc.gtradio

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.DynamicThemePack
import com.sc.gtradio.databinding.FragmentRadiolistBinding
import com.sc.gtradio.databinding.FragmentStationitemBinding
import com.sc.gtradio.media.*
import com.sc.gtradio.media.artwork.AlbumArtDownloader
import com.sc.gtradio.media.core.TracklistContract
import com.sc.gtradio.utils.InjectorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

class RadioList : Fragment() {
    var stationGroupId: String = ""
    var groupName: String = ""
    private var _binding: FragmentRadiolistBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null

    private data class StripSlot(
        val item: MediaItem,
        val mediaId: String,
        val imageView: ImageView
    )

    companion object {
        private const val REPEAT_CYCLES = 11
        private const val MIDDLE_CYCLE = REPEAT_CYCLES / 2
    }

    private var activeThemePack: DynamicThemePack? = null
    private var loadedStations: List<MediaItem> = emptyList()
    private val stationIconViews = HashMap<String, ImageView>()
    private val stripSlots = ArrayList<StripSlot>()
    private val stripViewsByMediaId = HashMap<String, MutableList<ImageView>>()
    private val stationOnBitmaps = HashMap<String, Bitmap?>()
    private val stationOffBitmaps = HashMap<String, Bitmap?>()
    private var lastAutoScrolledMediaId: String = ""

    private var stripItemSpanPx: Int = 0
    private var stripCycleWidthPx: Int = 0

    private var selectedStationTitle: String = ""
    private var centeredMediaId: String = ""
    private var pendingTappedMediaId: String? = null
    private var pendingTapTimestampMs: Long = 0L
    private var isUserScrollingStrip: Boolean = false
    private var isProgrammaticScroll: Boolean = false

    private var currentPlaybackState: GTRadioPlaybackState = EMPTY_PLAYBACK_STATE
    private var currentMetadata: MediaMetadata = NOTHING_PLAYING

    private val bgHandler = Handler(Looper.getMainLooper())
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var bgIndex = 0
    private var showingBgA = true
    private var volumeObserver: ContentObserver? = null
    private var lastTopInset: Int = 0
    private var lastBottomInset: Int = 0

    private fun applyThemeInsets(pack: DynamicThemePack?) {
        if (_binding == null) return
        val safeMarginPx = (16 * resources.displayMetrics.density).roundToInt()
        val bleed = pack?.bleedStatusBar ?: true
        binding.dynamicThemeRoot.setBackgroundColor(Color.BLACK)
        if (bleed) {
            binding.dynamicThemeRoot.updatePadding(top = 0)
            binding.themeHeaderTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = lastTopInset + safeMarginPx
            }
        } else {
            binding.dynamicThemeRoot.updatePadding(top = lastTopInset)
            binding.themeHeaderTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = safeMarginPx
            }
        }
        binding.root.findViewById<View>(R.id.nowPlaying)?.updatePadding(bottom = lastBottomInset)
        binding.themeControlsBlock.updatePadding(bottom = lastBottomInset)
        binding.themeStripControlsBlock.updatePadding(bottom = lastBottomInset)
    }

    private val scrollSnapRunnable = Runnable {
        if (_binding == null) return@Runnable
        val pack = activeThemePack ?: return@Runnable
        if (!isStripLayout(pack)) return@Runnable

        isUserScrollingStrip = false
        recenterInfiniteScrollIfNeeded()

        val slot = findClosestCenteredSlot() ?: return@Runnable
        val targetId = slot.mediaId
        centeredMediaId = targetId
        selectedStationTitle = slot.item.mediaMetadata.title?.toString() ?: ""

        scrollSlotToCenter(slot, smooth = true)

        val nowPlayingId = currentMetadata.mediaId ?: ""
        if (!currentPlaybackState.isPlaying || targetId != nowPlayingId) {
            lastAutoScrolledMediaId = targetId
            musicServiceConnection?.transportControls?.playFromMediaId(targetId, null)
        }
        updateDynamicThemePlaybackUi()
    }

    private val bgCycleRunnable = Runnable {
        cycleBackground()
    }

    private fun cycleBackground() {
        val pack = activeThemePack ?: return
        if (_binding == null) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bgList = if (isLandscape && pack.landscapeBgUris.isNotEmpty()) pack.landscapeBgUris else pack.portraitBgUris
        if (bgList.size > 1) {
            bgIndex = (bgIndex + 1) % bgList.size
            val nextUri = bgList[bgIndex]
            val reqW = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
            val reqH = resources.displayMetrics.heightPixels.coerceAtLeast(1080)
            val cacheKey = "${nextUri}_${reqW}x${reqH}"
            val cachedBmp = DynamicThemeManager.getCachedWallpaper(cacheKey)
            if (cachedBmp != null) {
                applyCrossfadeBg(cachedBmp, pack.bgFadeMs)
                bgHandler.postDelayed(bgCycleRunnable, pack.bgIntervalMs)
            } else {
                val ctx = context?.applicationContext
                if (ctx != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val nextBmp = DynamicThemeManager.loadDownsampledBackground(ctx, nextUri, reqW, reqH)
                        if (_binding != null && nextBmp != null) {
                            applyCrossfadeBg(nextBmp, pack.bgFadeMs)
                        }
                        bgHandler.postDelayed(bgCycleRunnable, pack.bgIntervalMs)
                    }
                } else {
                    bgHandler.postDelayed(bgCycleRunnable, pack.bgIntervalMs)
                }
            }
        }
    }

    private fun applyCrossfadeBg(nextBmp: Bitmap, fadeDur: Long) {
        if (_binding == null) return
        if (showingBgA) {
            binding.themeBgB.setImageBitmap(nextBmp)
            binding.themeBgB.animate().alpha(1f).setDuration(fadeDur).start()
            binding.themeBgA.animate().alpha(0f).setDuration(fadeDur).start()
        } else {
            binding.themeBgA.setImageBitmap(nextBmp)
            binding.themeBgA.animate().alpha(1f).setDuration(fadeDur).start()
            binding.themeBgB.animate().alpha(0f).setDuration(fadeDur).start()
        }
        showingBgA = !showingBgA
    }

    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            if (parentId == stationGroupId) {
                loadedStations = children
                listAdapter.submitList(children)
                setupDynamicThemeIfAvailable(children)
            }
        }
    }

    private val playbackStateObserver = Observer<GTRadioPlaybackState> { state ->
        currentPlaybackState = state ?: EMPTY_PLAYBACK_STATE
        updateDynamicThemePlaybackUi()
    }

    private val mediaMetadataObserver = Observer<MediaMetadata> { meta ->
        currentMetadata = meta ?: NOTHING_PLAYING
        updateDynamicThemePlaybackUi()
    }

    private val listAdapter = MediaItemListAdapter { clickedItem ->
        onStationClicked(clickedItem)
    }

    private val binding get() = _binding!!

    private fun isStripLayout(pack: DynamicThemePack): Boolean {
        return pack.layoutStyle.equals("strip", ignoreCase = true) ||
            pack.layoutStyle.equals("gta4", ignoreCase = true) ||
            pack.stationOffIconUris.isNotEmpty()
    }

    private fun recenterInfiniteScrollIfNeeded() {
        if (_binding == null || stripCycleWidthPx <= 0) return
        val scrollView = binding.themeStripIconScroll
        val curX = scrollView.scrollX
        val curCycle = curX / stripCycleWidthPx
        if (curCycle < 2 || curCycle > REPEAT_CYCLES - 3) {
            val deltaCycles = MIDDLE_CYCLE - curCycle
            val newX = (curX + deltaCycles * stripCycleWidthPx).coerceAtLeast(0)
            isProgrammaticScroll = true
            scrollView.scrollTo(newX, 0)
            isProgrammaticScroll = false
        }
    }

    private fun findClosestCenteredSlot(): StripSlot? {
        if (_binding == null || stripSlots.isEmpty()) return null
        val scrollView = binding.themeStripIconScroll
        val viewportCenter = scrollView.scrollX + (scrollView.width / 2)
        var bestSlot: StripSlot? = null
        var bestDist = Int.MAX_VALUE

        for (slot in stripSlots) {
            val iv = slot.imageView
            val childCenter = (iv.left + iv.right) / 2
            val dist = abs(childCenter - viewportCenter)
            if (dist < bestDist) {
                bestDist = dist
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun findClosestSlotForMediaId(mediaId: String): StripSlot? {
        if (_binding == null || mediaId.isBlank() || stripSlots.isEmpty()) return null
        val scrollView = binding.themeStripIconScroll
        val viewportCenter = scrollView.scrollX + (scrollView.width / 2)
        var bestSlot: StripSlot? = null
        var bestDist = Int.MAX_VALUE

        for (slot in stripSlots) {
            if (slot.mediaId != mediaId) continue
            val iv = slot.imageView
            val childCenter = (iv.left + iv.right) / 2
            val dist = abs(childCenter - viewportCenter)
            if (dist < bestDist) {
                bestDist = dist
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun scrollSlotToCenter(slot: StripSlot, smooth: Boolean = true) {
        if (_binding == null) return
        val iv = slot.imageView
        val scrollView = binding.themeStripIconScroll
        scrollView.post {
            if (_binding == null) return@post
            val targetX = ((iv.left + iv.right) / 2 - (scrollView.width / 2)).coerceAtLeast(0)
            isProgrammaticScroll = true
            if (smooth) {
                scrollView.smoothScrollTo(targetX, 0)
            } else {
                scrollView.scrollTo(targetX, 0)
            }
            scrollHandler.postDelayed({
                isProgrammaticScroll = false
                recenterInfiniteScrollIfNeeded()
            }, 240L)
        }
    }

    private fun scrollIconToCenter(mediaId: String, smooth: Boolean = true) {
        if (_binding == null || mediaId.isBlank()) return
        val slot = findClosestSlotForMediaId(mediaId) ?: return
        scrollSlotToCenter(slot, smooth)
    }

    private fun onStripSlotTapped(slot: StripSlot) {
        val clickedItem = slot.item
        val clickedId = slot.mediaId
        val transportControls = musicServiceConnection?.transportControls
        val activeMediaId = currentMetadata.mediaId ?: ""

        scrollHandler.removeCallbacks(scrollSnapRunnable)
        isUserScrollingStrip = false

        val title = clickedItem.mediaMetadata.title?.toString() ?: ""
        selectedStationTitle = title

        if (currentPlaybackState.isPlaying && clickedId == activeMediaId) {
            pendingTappedMediaId = null
            DynamicThemeManager.stopTuningNoise("user_stop")
            musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
            transportControls?.stop()
        } else {
            pendingTappedMediaId = clickedId
            pendingTapTimestampMs = SystemClock.elapsedRealtime()
            centeredMediaId = clickedId
            lastAutoScrolledMediaId = clickedId

            scrollSlotToCenter(slot, smooth = true)
            updateStripIconsHighlight(clickedId)

            if (binding.themeStripActiveStationName.text.toString() != title) {
                binding.themeStripActiveStationName.text = title
            }
            binding.themeStripActiveStationName.isSelected = true

            transportControls?.playFromMediaId(clickedId, null)
        }
        updateDynamicThemePlaybackUi()
    }

    private fun onStationClicked(clickedItem: MediaItem) {
        val pack = activeThemePack
        if (pack != null && isStripLayout(pack)) {
            val slot = findClosestSlotForMediaId(clickedItem.mediaId)
            if (slot != null) {
                onStripSlotTapped(slot)
                return
            }
        }

        val nowPlaying = musicServiceConnection?.nowPlaying?.value
        val transportControls = musicServiceConnection?.transportControls
        selectedStationTitle = clickedItem.mediaMetadata.title?.toString() ?: ""

        if (clickedItem.mediaId == nowPlaying?.mediaId) {
            musicServiceConnection?.playbackState?.value?.let { state ->
                if (state.isPlaying) {
                    DynamicThemeManager.stopTuningNoise("user_stop")
                    musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
                    transportControls?.stop()
                } else {
                    transportControls?.play()
                }
            }
        } else {
            transportControls?.playFromMediaId(clickedItem.mediaId, null)
        }
        updateDynamicThemePlaybackUi()
    }

    private fun toggleRadioPower() {
        val transportControls = musicServiceConnection?.transportControls
        if (currentPlaybackState.isPlaying) {
            DynamicThemeManager.stopTuningNoise("user_power_off")
            musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
            transportControls?.stop()
        } else {
            val targetItem = loadedStations.find { it.mediaId == centeredMediaId }
                ?: loadedStations.find { it.mediaMetadata.title?.toString().equals(selectedStationTitle, ignoreCase = true) }
                ?: loadedStations.firstOrNull()
            if (targetItem != null) {
                centeredMediaId = targetItem.mediaId
                selectedStationTitle = targetItem.mediaMetadata.title?.toString() ?: ""
                lastAutoScrolledMediaId = centeredMediaId
                scrollIconToCenter(centeredMediaId, smooth = true)
                transportControls?.playFromMediaId(targetItem.mediaId, null)
            } else {
                transportControls?.play()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        stationGroupId = arguments?.getString("stationGroupId") ?: ""
        groupName = arguments?.getString("groupName") ?: ""
        if (musicServiceConnection == null) {
            musicServiceConnection = InjectorUtils.provideMusicServiceConnection(requireContext())
        }
        musicServiceConnection?.subscribe(stationGroupId, subscriptionCallback)
        _binding = FragmentRadiolistBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.stationList.layoutManager = LinearLayoutManager(context)
        binding.stationList.adapter = listAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            lastTopInset = systemBars.top
            lastBottomInset = systemBars.bottom
            applyThemeInsets(activeThemePack)
            insets
        }

        val targetName = if (groupName.isNotBlank()) groupName else stationGroupId
        val cachedPack = if (DynamicThemeManager.isDynamicThemeEnabled(requireContext())) {
            DynamicThemeManager.getCachedThemeForSeries(targetName)
        } else null

        if (loadedStations.isNotEmpty()) {
            listAdapter.submitList(loadedStations)
            setupDynamicThemeIfAvailable(loadedStations)
        } else if (cachedPack?.hasFullThemeUi == true) {
            applyDynamicThemeShell(cachedPack)
        }

        musicServiceConnection?.playbackState?.observe(viewLifecycleOwner, playbackStateObserver)
        musicServiceConnection?.nowPlaying?.observe(viewLifecycleOwner, mediaMetadataObserver)

        setupVolumeControls()

        binding.themeRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioLabel.setOnClickListener { toggleRadioPower() }

        binding.themeStripIconScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingTappedMediaId = null
                    isUserScrollingStrip = true
                    scrollHandler.removeCallbacks(scrollSnapRunnable)
                    recenterInfiniteScrollIfNeeded()
                }
                MotionEvent.ACTION_MOVE -> {
                    pendingTappedMediaId = null
                    isUserScrollingStrip = true
                    scrollHandler.removeCallbacks(scrollSnapRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollHandler.removeCallbacks(scrollSnapRunnable)
                    scrollHandler.postDelayed(scrollSnapRunnable, 140L)
                }
            }
            false
        }

        binding.themeStripIconScroll.viewTreeObserver.addOnScrollChangedListener {
            if (_binding == null || isProgrammaticScroll) return@addOnScrollChangedListener
            val pack = activeThemePack ?: return@addOnScrollChangedListener
            if (!isStripLayout(pack)) return@addOnScrollChangedListener

            if (isUserScrollingStrip) {
                val centerSlot = findClosestCenteredSlot()
                if (centerSlot != null) {
                    val cId = centerSlot.mediaId
                    val cTitle = centerSlot.item.mediaMetadata.title?.toString() ?: ""
                    if (cId != centeredMediaId) {
                        centeredMediaId = cId
                        selectedStationTitle = cTitle
                        if (binding.themeStripActiveStationName.text.toString() != cTitle) {
                            binding.themeStripActiveStationName.text = cTitle
                        }
                        binding.themeStripActiveStationName.isSelected = true
                        updateStripIconsHighlight(if (currentPlaybackState.isPlaying) centeredMediaId else "")
                    }
                }
                scrollHandler.removeCallbacks(scrollSnapRunnable)
                scrollHandler.postDelayed(scrollSnapRunnable, 150L)
            }
        }
    }

    private fun updateStripIconsHighlight(activeId: String) {
        val pack = activeThemePack ?: return
        val hasOffIcons = pack.stationOffIconUris.isNotEmpty()
        for ((mId, views) in stripViewsByMediaId) {
            val isThisActive = activeId.isNotBlank() && mId == activeId
            val bmp = if (isThisActive) stationOnBitmaps[mId] else stationOffBitmaps[mId]
            val alphaVal = if (isThisActive || hasOffIcons) 1.0f else 0.28f
            for (iv in views) {
                if (bmp != null) iv.setImageBitmap(bmp)
                iv.alpha = alphaVal
            }
        }
    }

    private fun setupVolumeControls() {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun syncBarsFromSystem() {
            if (_binding == null) return
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val bars = ((curSys.toFloat() / maxSys.toFloat()) * binding.themeVolumeBar.maxBars).roundToInt()
            binding.themeVolumeBar.currentBars = bars
            binding.themeStripVolumeBar.currentBars = bars
        }
        syncBarsFromSystem()

        val onVolumeChanged: (Int, Int) -> Unit = { bars, maxBars ->
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = ((bars.toFloat() / maxBars.toFloat()) * maxSys).roundToInt().coerceIn(0, maxSys)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            binding.themeVolumeBar.currentBars = bars
            binding.themeStripVolumeBar.currentBars = bars
        }
        binding.themeVolumeBar.onVolumeBarsChanged = onVolumeChanged
        binding.themeStripVolumeBar.onVolumeBarsChanged = onVolumeChanged

        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncBarsFromSystem()
            }
        }
        requireContext().contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
        volumeObserver = obs
    }

    private fun setupDynamicThemeIfAvailable(children: List<MediaItem>) {
        if (_binding == null) return
        val seriesName = children.firstOrNull()?.mediaMetadata?.subtitle?.toString()
            ?: children.firstOrNull()?.mediaMetadata?.groupName
            ?: groupName.ifBlank { stationGroupId }
        if (seriesName.isBlank() || !DynamicThemeManager.isDynamicThemeEnabled(requireContext())) {
            applyDynamicTheme(null, children)
            return
        }

        val cachedPack = DynamicThemeManager.getCachedThemeForSeries(seriesName)
        if (cachedPack != null) {
            applyDynamicTheme(if (cachedPack.hasFullThemeUi) cachedPack else null, children)
        } else {
            val ctx = context?.applicationContext ?: return
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val pack = DynamicThemeManager.findThemeForSeries(ctx, seriesName)
                if (pack != null && pack.hasFullThemeUi) {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val bgList = if (isLandscape && pack.landscapeBgUris.isNotEmpty()) pack.landscapeBgUris else pack.portraitBgUris
                    if (bgList.isNotEmpty()) {
                        val reqW = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
                        val reqH = resources.displayMetrics.heightPixels.coerceAtLeast(1080)
                        DynamicThemeManager.loadDownsampledBackground(ctx, bgList[0], reqW, reqH)
                    }
                    for (item in children) {
                        val stTitle = item.mediaMetadata.title?.toString() ?: ""
                        val onUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, stTitle)
                        val offUri = DynamicThemeManager.resolveStationIconUri(pack.stationOffIconUris, stTitle)
                        DynamicThemeManager.loadBitmap(ctx, onUri, 256)
                        DynamicThemeManager.loadBitmap(ctx, offUri, 256)
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (_binding != null && isAdded) {
                        applyDynamicTheme(if (pack?.hasFullThemeUi == true) pack else null, loadedStations.ifEmpty { children })
                    }
                }
            }
        }
    }

    private fun applyDynamicThemeShell(pack: DynamicThemePack) {
        if (_binding == null) return
        activeThemePack = pack
        applyThemeInsets(pack)
        if (pack.sfxMode.equals("click", ignoreCase = true)) {
            DynamicThemeManager.preloadClickSound(requireContext(), pack)
        }
        if (loadedStations.isNotEmpty()) {
            binding.defaultRadioContainer.visibility = View.GONE
            binding.dynamicThemeRoot.visibility = View.VISIBLE
            (activity as? MainActivity)?.setFullscreenThemeMode(true)
        }

        val stripMode = isStripLayout(pack)
        binding.themeControlsBlock.visibility = if (stripMode) View.GONE else View.VISIBLE
        binding.themeStripControlsBlock.visibility = if (stripMode) View.VISIBLE else View.GONE

        // Apply Header Title & Font
        binding.themeHeaderTitle.text = pack.headerTitle
        binding.themeHeaderTitle.setTextColor(pack.headerColor)
        pack.headerTypeface?.let { binding.themeHeaderTitle.typeface = it }

        val bodyViews = listOf(
            binding.themeNowPlayingLabel,
            binding.themeSongTitle,
            binding.themeSongArtist,
            binding.themeSongAlbum,
            binding.themeRadioLabel,
            binding.themeRadioState,
            binding.themeVolumeLabel,
            binding.themeRadioStationLabel,
            binding.themeActiveStationName,
            binding.themeStripRadioLabel,
            binding.themeStripRadioState,
            binding.themeStripVolumeLabel,
            binding.themeStripStationLabel,
            binding.themeStripActiveStationName
        )
        pack.bodyTypeface?.let { tf ->
            bodyViews.forEach { it.typeface = tf }
        }

        binding.themeVolumeBar.continuousMode = false
        binding.themeVolumeBar.activeBarColor = pack.volumeBarColor

        binding.themeStripVolumeBar.continuousMode = true
        binding.themeStripVolumeBar.activeBarColor = pack.volumeBarColor
        binding.themeStripVolumeBar.inactiveBarColor = Color.parseColor("#3B3B3B")

        if (!stripMode) {
            binding.themeNowPlayingLabel.setTextColor(pack.activeTextColor)
            binding.themeSongTitle.setTextColor(pack.headerColor)
            binding.themeSongArtist.setTextColor(pack.activeTextColor)
            binding.themeSongAlbum.setTextColor(pack.labelColor)
            binding.themeRadioLabel.setTextColor(pack.labelColor)
            binding.themeVolumeLabel.setTextColor(pack.labelColor)
            binding.themeRadioStationLabel.setTextColor(pack.labelColor)
            binding.themeActiveStationName.setTextColor(pack.labelColor)
        }

        // Setup Initial Background & Start Crossfade Cycle
        bgHandler.removeCallbacks(bgCycleRunnable)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bgList = if (isLandscape && pack.landscapeBgUris.isNotEmpty()) pack.landscapeBgUris else pack.portraitBgUris
        if (bgList.isNotEmpty()) {
            bgIndex = 0
            showingBgA = true
            binding.themeBgA.alpha = 1f
            binding.themeBgB.alpha = 0f
            val uri = bgList[0]
            val reqW = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
            val reqH = resources.displayMetrics.heightPixels.coerceAtLeast(1080)
            val cacheKey = "${uri}_${reqW}x${reqH}"
            val cachedBmp = DynamicThemeManager.getCachedWallpaper(cacheKey)
            if (cachedBmp != null) {
                binding.themeBgA.setImageBitmap(cachedBmp)
            } else {
                val ctx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = DynamicThemeManager.loadDownsampledBackground(ctx, uri, reqW, reqH)
                    if (_binding != null && bmp != null && bgIndex == 0 && showingBgA) {
                        binding.themeBgA.setImageBitmap(bmp)
                    }
                }
            }
            if (bgList.size > 1) {
                bgHandler.postDelayed(bgCycleRunnable, pack.bgIntervalMs)
            }
        }
    }

    private fun applyDynamicTheme(pack: DynamicThemePack?, children: List<MediaItem>) {
        if (_binding == null) return

        if (pack == null || !pack.hasFullThemeUi) {
            activeThemePack = null
            binding.dynamicThemeRoot.visibility = View.GONE
            binding.defaultRadioContainer.visibility = View.VISIBLE
            (activity as? MainActivity)?.setFullscreenThemeMode(false)
            listAdapter.submitList(children)
            return
        }

        if (children.isEmpty()) {
            applyDynamicThemeShell(pack)
            return
        }
        binding.defaultRadioContainer.visibility = View.GONE
        binding.dynamicThemeRoot.visibility = View.VISIBLE
        (activity as? MainActivity)?.setFullscreenThemeMode(true)
        applyDynamicThemeShell(pack)

        val stripMode = isStripLayout(pack)

        // Build Station Icons
        binding.themeIconRow1.removeAllViews()
        binding.themeIconRow2.removeAllViews()
        binding.themeStripIconRow.removeAllViews()
        stationIconViews.clear()
        stripSlots.clear()
        stripViewsByMediaId.clear()
        stationOnBitmaps.clear()
        stationOffBitmaps.clear()

        val density = resources.displayMetrics.density
        val iconSizePx = ((if (stripMode) 64 else 52) * density).roundToInt()
        val marginPx = ((if (stripMode) 4 else 3) * density).roundToInt()
        stripItemSpanPx = iconSizePx + (marginPx * 2)
        stripCycleWidthPx = children.size * stripItemSpanPx

        var missingIcons = false
        for (item in children) {
            val mId = item.mediaId
            val stTitle = item.mediaMetadata.title?.toString() ?: ""
            val onUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, stTitle)
            val offUri = DynamicThemeManager.resolveStationIconUri(pack.stationOffIconUris, stTitle)

            val onBmp: Bitmap? = DynamicThemeManager.getCachedBitmap(onUri, 256)
                ?: item.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 256) }
            val offBmp: Bitmap? = DynamicThemeManager.getCachedBitmap(offUri, 256)
                ?: onBmp

            if (onBmp == null || offBmp == null) {
                missingIcons = true
            }

            stationOnBitmaps[mId] = onBmp
            stationOffBitmaps[mId] = offBmp
        }

        if (missingIcons) {
            val ctx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                for (item in children) {
                    val mId = item.mediaId
                    val stTitle = item.mediaMetadata.title?.toString() ?: ""
                    val onUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, stTitle)
                    val offUri = DynamicThemeManager.resolveStationIconUri(pack.stationOffIconUris, stTitle)

                    val onBmp = stationOnBitmaps[mId] ?: (DynamicThemeManager.loadBitmap(ctx, onUri, 256)
                        ?: item.mediaMetadata.artworkUri?.let { DynamicThemeManager.loadBitmap(ctx, it, 256) })
                    val offBmp = stationOffBitmaps[mId] ?: (DynamicThemeManager.loadBitmap(ctx, offUri, 256)
                        ?: onBmp)

                    stationOnBitmaps[mId] = onBmp
                    stationOffBitmaps[mId] = offBmp
                }
                withContext(Dispatchers.Main.immediate) {
                    if (_binding != null && isAdded) {
                        for ((mId, views) in stripViewsByMediaId) {
                            val offBmp = stationOffBitmaps[mId]
                            if (offBmp != null) {
                                for (iv in views) iv.setImageBitmap(offBmp)
                            }
                        }
                        for ((mId, iv) in stationIconViews) {
                            val onBmp = stationOnBitmaps[mId]
                            if (onBmp != null) iv.setImageBitmap(onBmp)
                        }
                        updateDynamicThemePlaybackUi()
                    }
                }
            }
        }

        if (stripMode && children.isNotEmpty()) {
            val hasOffIcons = pack.stationOffIconUris.isNotEmpty()
            for (cycle in 0 until REPEAT_CYCLES) {
                for (item in children) {
                    val mId = item.mediaId
                    val offBmp = stationOffBitmaps[mId]
                    val iv = ImageView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                            setMargins(marginPx, marginPx, marginPx, marginPx)
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        if (offBmp != null) {
                            setImageBitmap(offBmp)
                        } else {
                            setImageResource(R.drawable.outline_radio_24)
                        }
                        alpha = if (hasOffIcons) 1.0f else 0.28f
                    }
                    val slot = StripSlot(item, mId, iv)
                    iv.setOnClickListener { onStripSlotTapped(slot) }
                    stripSlots.add(slot)
                    stripViewsByMediaId.getOrPut(mId) { ArrayList() }.add(iv)
                    if (cycle == MIDDLE_CYCLE) {
                        stationIconViews[mId] = iv
                    }
                    binding.themeStripIconRow.addView(iv)
                }
            }

            binding.themeStripIconScroll.post {
                if (_binding == null) return@post
                val sidePad = ((binding.themeStripIconScroll.width - stripItemSpanPx) / 2).coerceAtLeast(0)
                binding.themeStripIconScroll.setPadding(0, 0, 0, 0)
                binding.themeStripIconRow.setPadding(sidePad, 0, sidePad, 0)
                val initialId = centeredMediaId.ifBlank {
                    currentMetadata.mediaId ?: children.firstOrNull()?.mediaId ?: ""
                }
                if (initialId.isNotBlank()) {
                    centeredMediaId = initialId
                    val middleIv = stationIconViews[initialId]
                    if (middleIv != null) {
                        val targetX = ((middleIv.left + middleIv.right) / 2 - (binding.themeStripIconScroll.width / 2)).coerceAtLeast(0)
                        isProgrammaticScroll = true
                        binding.themeStripIconScroll.scrollTo(targetX, 0)
                        isProgrammaticScroll = false
                    }
                }
            }
        } else {
            children.forEachIndexed { idx, item ->
                val mId = item.mediaId
                val onBmp = stationOnBitmaps[mId]
                val iv = ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                        setMargins(marginPx, marginPx, marginPx, marginPx)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (onBmp != null) {
                        setImageBitmap(onBmp)
                    } else {
                        setImageResource(R.drawable.outline_radio_24)
                    }
                    alpha = 0.28f
                    setOnClickListener { onStationClicked(item) }
                }
                stationIconViews[mId] = iv
                if (idx < 6) {
                    binding.themeIconRow1.addView(iv)
                } else {
                    binding.themeIconRow2.addView(iv)
                }
            }
        }

        if (selectedStationTitle.isBlank()) {
            selectedStationTitle = children.firstOrNull()?.mediaMetadata?.title?.toString() ?: ""
        }
        if (centeredMediaId.isBlank()) {
            centeredMediaId = children.firstOrNull()?.mediaId ?: ""
        }
        updateDynamicThemePlaybackUi()
    }

    private fun updateDynamicThemePlaybackUi() {
        val pack = activeThemePack ?: return
        if (_binding == null) return

        val stripMode = isStripLayout(pack)
        val isPlaying = currentPlaybackState.isPlaying
        val sessionExtras = musicServiceConnection?.sessionExtras?.value
        val playbackMode = sessionExtras?.getString(TracklistContract.EXTRA_PLAYBACK_MODE)
            ?: currentPlaybackState.extras?.getString(TracklistContract.EXTRA_PLAYBACK_MODE)

        val activeMediaId = currentMetadata.mediaId
            ?: currentPlaybackState.extras?.getString(TracklistContract.EXTRA_STATION_ID)
            ?: sessionExtras?.getString(TracklistContract.EXTRA_STATION_ID)
            ?: ""
        val activeGroupId = currentPlaybackState.extras?.getString(TracklistContract.EXTRA_STATION_GROUP_ID)
            ?: sessionExtras?.getString(TracklistContract.EXTRA_STATION_GROUP_ID)

        // Check if pending tapped station should expire or cleared
        if (pendingTappedMediaId != null) {
            if (activeMediaId == pendingTappedMediaId || SystemClock.elapsedRealtime() - pendingTapTimestampMs > 4500L) {
                pendingTappedMediaId = null
            }
        }

        val isTracklist = playbackMode == "TRACKLIST" || activeMediaId.startsWith("tracklist_")
        val isDifferentGroup = !activeGroupId.isNullOrBlank() && stationGroupId.isNotBlank() && activeGroupId != stationGroupId

        val activeBelongsToThisSeries = if (isTracklist || isDifferentGroup) {
            false
        } else {
            (activeMediaId.isNotBlank() && loadedStations.any { it.mediaId == activeMediaId }) ||
            (!activeGroupId.isNullOrBlank() && activeGroupId == stationGroupId) ||
            (!currentMetadata.groupName.isNullOrBlank() && loadedStations.any { it.mediaMetadata.albumTitle?.toString().equals(currentMetadata.groupName, ignoreCase = true) }) ||
            (!currentMetadata.stationName.isNullOrBlank() && loadedStations.any { it.mediaMetadata.title?.toString().equals(currentMetadata.stationName, ignoreCase = true) })
        }
        val effectivePlaying = isPlaying && activeBelongsToThisSeries

        val activeStationFromMeta = if (activeBelongsToThisSeries) {
            currentMetadata.stationName
                ?: loadedStations.find { it.mediaId == activeMediaId }?.mediaMetadata?.title?.toString()
                ?: ""
        } else ""

        if (stripMode) {
            val statusColor = if (effectivePlaying) pack.activeTextColor else pack.labelColor
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.setTextColor(statusColor)

            binding.themeStripRadioLabel.setTextColor(statusColor)
            binding.themeStripRadioState.text = if (effectivePlaying) "On" else "Off"
            binding.themeStripRadioState.setTextColor(statusColor)
            binding.themeStripVolumeLabel.setTextColor(statusColor)
            binding.themeStripStationLabel.setTextColor(statusColor)

            val selectedItem = if (pendingTappedMediaId != null) {
                loadedStations.find { it.mediaId == pendingTappedMediaId }
            } else if (!isUserScrollingStrip && activeBelongsToThisSeries) {
                loadedStations.find { it.mediaId == activeMediaId }
            } else {
                loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.firstOrNull()
            }
            val selectedMediaId = selectedItem?.mediaId ?: ""
            val previouslyCenteredId = centeredMediaId
            if (!isUserScrollingStrip && selectedMediaId.isNotBlank()) {
                centeredMediaId = selectedMediaId
            }

            val displayStationTitle = if (isUserScrollingStrip) {
                loadedStations.find { it.mediaId == centeredMediaId }?.mediaMetadata?.title?.toString() ?: ""
            } else {
                selectedItem?.mediaMetadata?.title?.toString() ?: ""
            }
            if (binding.themeStripActiveStationName.text.toString() != displayStationTitle) {
                binding.themeStripActiveStationName.text = displayStationTitle
            }
            binding.themeStripActiveStationName.isSelected = true
            binding.themeStripActiveStationName.setTextColor(statusColor)

            val highlightedId = if (effectivePlaying || pendingTappedMediaId != null) {
                if (isUserScrollingStrip) centeredMediaId else selectedMediaId
            } else ""
            updateStripIconsHighlight(highlightedId)

            if (!isUserScrollingStrip && selectedMediaId.isNotBlank() &&
                selectedMediaId != lastAutoScrolledMediaId &&
                selectedMediaId != previouslyCenteredId) {
                lastAutoScrolledMediaId = selectedMediaId
                scrollIconToCenter(selectedMediaId, smooth = true)
            }

            val isSongOrShow = effectivePlaying && !DynamicThemeManager.isPlayingTuningNoise() &&
                currentMetadata.isSong

            if (effectivePlaying) {
                binding.themeSongTitle.setTextColor(pack.activeTextColor)
                binding.themeSongArtist.setTextColor(pack.activeTextColor)
                binding.themeSongAlbum.setTextColor(pack.activeTextColor)

                if (isSongOrShow) {
                    val songTitle = currentMetadata.title?.toString() ?: ""
                    val cleanArtist = currentMetadata.artist?.toString() ?: ""
                    val cleanAlbum = currentMetadata.albumArtist?.toString() ?: currentMetadata.albumTitle?.toString() ?: ""

                    binding.themeSongTitle.text = songTitle.uppercase()
                    binding.themeSongArtist.text = cleanArtist.uppercase()
                    binding.themeSongAlbum.visibility = View.VISIBLE
                    binding.themeSongAlbum.text = cleanAlbum.uppercase()
                } else {
                    binding.themeSongTitle.text = displayStationTitle.uppercase()
                    binding.themeSongArtist.text = ""
                    binding.themeSongAlbum.visibility = View.VISIBLE
                    binding.themeSongAlbum.text = ""
                }

                val artBmp = stationOnBitmaps[selectedMediaId] ?: currentMetadata.iconBitmap
                val songArtBmp = if (isSongOrShow && currentMetadata.iconBitmap != null) {
                    currentMetadata.iconBitmap
                } else {
                    artBmp
                }
                if (songArtBmp != null) {
                    binding.themeSongArt.setImageBitmap(songArtBmp)
                } else {
                    binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
                }
                binding.themeSongArt.alpha = 1.0f
            } else {
                val offArtBmp = stationOnBitmaps[selectedMediaId] ?: stationOffBitmaps[selectedMediaId]
                if (offArtBmp != null) {
                    binding.themeSongArt.setImageBitmap(offArtBmp)
                } else {
                    binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
                }
                binding.themeSongArt.alpha = 0.45f
                binding.themeSongTitle.text = ""
                binding.themeSongArtist.text = ""
                binding.themeSongAlbum.visibility = View.VISIBLE
                binding.themeSongAlbum.text = ""
            }
            return
        }

        // Standard Dynamic Theme Grid UI
        binding.themeRadioState.text = if (effectivePlaying) "ON" else "OFF"
        binding.themeRadioState.setTextColor(if (effectivePlaying) pack.activeTextColor else pack.labelColor)

        val gridStationTitle = if (effectivePlaying) {
            activeStationFromMeta.ifBlank {
                loadedStations.find { it.mediaId == activeMediaId }?.mediaMetadata?.title?.toString() ?: ""
            }
        } else {
            loadedStations.find { it.mediaId == centeredMediaId }?.mediaMetadata?.title?.toString()
                ?: loadedStations.firstOrNull()?.mediaMetadata?.title?.toString()
                ?: ""
        }
        val gridStationUpper = gridStationTitle.uppercase()
        if (binding.themeActiveStationName.text.toString() != gridStationUpper) {
            binding.themeActiveStationName.text = gridStationUpper
        }
        binding.themeActiveStationName.isSelected = true

        for ((mediaId, iv) in stationIconViews) {
            val isThisActive = effectivePlaying && mediaId == activeMediaId
            iv.alpha = if (isThisActive) 1.0f else 0.28f
        }

        val isSongOrShow = effectivePlaying && !DynamicThemeManager.isPlayingTuningNoise() &&
            currentMetadata.isSong
        if (effectivePlaying) {
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.visibility = View.VISIBLE
            binding.themeSongArt.alpha = 1.0f
            val songTitle = currentMetadata.title?.toString() ?: ""
            val cleanArtist = currentMetadata.artist?.toString() ?: ""
            val cleanAlbum = currentMetadata.albumArtist?.toString() ?: currentMetadata.albumTitle?.toString() ?: ""

            if (isSongOrShow || songTitle.isNotBlank()) {
                binding.themeSongTitle.visibility = View.VISIBLE
                binding.themeSongTitle.text = songTitle.uppercase()

                if (cleanArtist.isNotBlank()) {
                    binding.themeSongArtist.visibility = View.VISIBLE
                    binding.themeSongArtist.text = cleanArtist.uppercase()
                } else {
                    binding.themeSongArtist.visibility = View.GONE
                }

                if (cleanAlbum.isNotBlank()) {
                    binding.themeSongAlbum.visibility = View.VISIBLE
                    binding.themeSongAlbum.text = cleanAlbum.uppercase()
                } else {
                    binding.themeSongAlbum.visibility = View.GONE
                }
            } else {
                binding.themeSongTitle.visibility = View.VISIBLE
                binding.themeSongTitle.text = gridStationUpper
                binding.themeSongArtist.text = ""
                binding.themeSongArtist.visibility = View.GONE
                binding.themeSongAlbum.text = ""
                binding.themeSongAlbum.visibility = View.GONE
            }

            var artBmp = currentMetadata.iconBitmap
            if (artBmp == null && cleanArtist.isNotBlank() && songTitle.isNotBlank()) {
                artBmp = AlbumArtDownloader.getCachedAlbumArt(requireContext(), cleanArtist, songTitle)
            }
            if (artBmp == null) {
                artBmp = stationOnBitmaps[activeMediaId]
            }
            if (artBmp != null) {
                binding.themeSongArt.setImageBitmap(artBmp)
            } else {
                binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
            }
            binding.themeSongArt.visibility = View.VISIBLE
        } else {
            binding.themeNowPlayingGroup.visibility = View.INVISIBLE
            binding.themeSongTitle.text = ""
            binding.themeSongArtist.text = ""
            binding.themeSongAlbum.text = ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bgHandler.removeCallbacksAndMessages(null)
        scrollHandler.removeCallbacksAndMessages(null)
        volumeObserver?.let {
            try { requireContext().contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        volumeObserver = null
        if (activeThemePack?.sfxMode.equals("click", ignoreCase = true)) {
            DynamicThemeManager.releaseClickSound()
        }
        (activity as? MainActivity)?.setFullscreenThemeMode(false)
        _binding = null
    }
}

class MediaItemListAdapter(
    private val itemClickedListener: (MediaItem) -> Unit
) : ListAdapter<MediaItem, MediaViewHolder>(MediaItemDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentStationitemBinding.inflate(inflater, parent, false)
        return MediaViewHolder(binding, itemClickedListener)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int, payloads: MutableList<Any>) {
        val mediaItem = getItem(position)
        if (payloads.isEmpty()) {
            holder.item = mediaItem
            holder.titleView.text = mediaItem.mediaMetadata.title
            val cachedBmp = mediaItem.mediaMetadata.artworkUri?.let { DynamicThemeManager.getCachedBitmap(it, 256) }
                ?: mediaItem.mediaMetadata.iconBitmap
            if (cachedBmp != null) {
                holder.albumArt.setImageBitmap(cachedBmp)
            } else {
                holder.albumArt.setImageResource(R.drawable.outline_radio_24)
                mediaItem.mediaMetadata.artworkUri?.let { artUri ->
                    val ctx = holder.itemView.context.applicationContext
                    CoroutineScope(Dispatchers.IO).launch {
                        val loaded = DynamicThemeManager.loadBitmap(ctx, artUri, 256)
                        if (loaded != null) {
                            withContext(Dispatchers.Main.immediate) {
                                if (holder.bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                                    holder.albumArt.setImageBitmap(loaded)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }
}

class MediaViewHolder(
    binding: FragmentStationitemBinding,
    itemClickedListener: (MediaItem) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    val titleView: TextView = binding.title
    val albumArt: ImageView = binding.albumArt
    var item: MediaItem? = null

    init {
        binding.root.setOnClickListener { item?.let { itemClickedListener(it) } }
    }
}

val MediaItemDiffCallback = object : DiffUtil.ItemCallback<MediaItem>() {
    override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
        oldItem.mediaId == newItem.mediaId

    override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) =
        oldItem.mediaId == newItem.mediaId

    override fun getChangePayload(oldItem: MediaItem, newItem: MediaItem): Nothing? = null
}
