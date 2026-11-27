package com.sc.gtradio

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
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
import com.sc.gtradio.media.rpf7.Rpf7MetadataRepository
import com.sc.gtradio.utils.InjectorUtils
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.min
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
        private const val PREF_LOUDNESS = "loudness_normalization_enabled"
        private const val PREF_CAR_EQ = "car_stereo_eq_enabled"
        private const val PREF_WHEEL_ARC_SIDE = "theme_wheel_arc_side"
        private const val RADIO_OFF_COMMIT_KEY = "__RADIO_OFF__"
        private const val STRIP_OFF_COMMIT_KEY = "__STRIP_OFF__"
    }

    private var activeThemePack: DynamicThemePack? = null
    private var loadedStations: List<MediaItem> = emptyList()
    private val stationIconViews = HashMap<String, ImageView>()
    private val stripSlots = ArrayList<StripSlot>()
    private val stripViewsByMediaId = HashMap<String, MutableList<ImageView>>()
    private val stationOnBitmaps = HashMap<String, Bitmap?>()
    private val stationOffBitmaps = HashMap<String, Bitmap?>()
    private var wheelOffIconBitmap: Bitmap? = null
    private var wheelScrubbingSlot: RadioWheelView.WheelSlot? = null
    private var isWheelInteracting: Boolean = false
    private var pendingWheelCommitKey: String? = null
    private var pendingWheelCommitUntilMs: Long = 0L
    private var lastAutoScrolledMediaId: String = ""

    private var stripItemSpanPx: Int = 0
    private var stripCycleWidthPx: Int = 0

    private var selectedStationTitle: String = ""
    private var centeredMediaId: String = ""
    private var pendingStripCommitKey: String? = null
    private var pendingStripCommitUntilMs: Long = 0L
    private var stripPendingPostRunnable: Runnable? = null
    private var stripProgrammaticEndRunnable: Runnable? = null
    private var stripTouchDownScrollX: Int = 0
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

    private val settingsPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (_binding == null || !isAdded) return@OnSharedPreferenceChangeListener
        when (key) {
            PREF_LOUDNESS, PREF_CAR_EQ -> updateSettingsCardValues()
            PREF_WHEEL_ARC_SIDE -> {
                updateSettingsCardValues()
                val pack = activeThemePack
                if (pack != null && isWheelTheme(pack)) {
                    applyWheelOrientationAndStage(pack, loadedStations.size)
                }
            }
        }
    }

    private fun applyThemeInsets(pack: DynamicThemePack?) {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val safeMarginPx = (16 * density).roundToInt()
        val bleed = pack?.bleedStatusBar ?: true
        val wheelMode = pack != null && isWheelTheme(pack)
        val headerStartPx = ((if (wheelMode) 18 else 24) * density).roundToInt()
        val headerEndPx = ((if (wheelMode) 18 else 16) * density).roundToInt()
        binding.dynamicThemeRoot.setBackgroundColor(Color.BLACK)
        if (bleed) {
            binding.dynamicThemeRoot.updatePadding(top = 0)
            binding.themeHeaderTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = lastTopInset + safeMarginPx
                marginStart = headerStartPx
                marginEnd = headerEndPx
            }
        } else {
            binding.dynamicThemeRoot.updatePadding(top = lastTopInset)
            binding.themeHeaderTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = safeMarginPx
                marginStart = headerStartPx
                marginEnd = headerEndPx
            }
        }
        binding.root.findViewById<View>(R.id.nowPlaying)?.updatePadding(bottom = lastBottomInset)
        binding.themeControlsBlock.updatePadding(bottom = lastBottomInset)
        binding.themeStripControlsBlock.updatePadding(bottom = lastBottomInset)
        binding.themeSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = lastBottomInset + (12 * density).roundToInt()
        }
        if (pack != null && isWheelTheme(pack) && isArcWheelMode(pack, loadedStations.size)) {
            binding.themeWheelStage.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = lastTopInset + (6 * density).roundToInt()
            }
        }
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
            pendingStripCommitKey = targetId
            pendingStripCommitUntilMs = SystemClock.elapsedRealtime() + 3500L
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
        return !isWheelTheme(pack) && (
            pack.layoutStyle.equals("strip", ignoreCase = true) ||
                pack.layoutStyle.equals("gta4", ignoreCase = true) ||
                pack.stationOffIconUris.isNotEmpty()
            )
    }

    private fun isWheelTheme(pack: DynamicThemePack): Boolean {
        return pack.layoutStyle.equals("wheel", ignoreCase = true) ||
            pack.layoutStyle.equals("wheel_arc", ignoreCase = true)
    }

    private fun isArcWheelMode(pack: DynamicThemePack, stationCount: Int): Boolean {
        val totalSlots = stationCount + 1
        return pack.layoutStyle.equals("wheel_arc", ignoreCase = true) ||
            (pack.layoutStyle.equals("wheel", ignoreCase = true) && totalSlots > pack.autoArcAbove)
    }

    private fun updateSettingsCardValues() {
        val b = _binding ?: return
        val ctx = context?.applicationContext ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val loudnessOn = prefs.getBoolean(PREF_LOUDNESS, true)
        val eqOn = prefs.getBoolean(PREF_CAR_EQ, false)
        val arcSide = prefs.getString(PREF_WHEEL_ARC_SIDE, "left") ?: "left"
        b.themeSettingsLoudnessValue.text = if (loudnessOn) "< On >" else "< Off >"
        b.themeSettingsEqValue.text = if (eqOn) "< On >" else "< Off >"
        b.themeSettingsWheelSideValue.text = if (arcSide.equals("right", ignoreCase = true)) "< Right >" else "< Left >"
    }

    private fun applyWheelOrientationAndStage(pack: DynamicThemePack, stationCount: Int) {
        val b = _binding ?: return
        val ctx = context?.applicationContext ?: return
        val density = resources.displayMetrics.density
        val arcMode = isArcWheelMode(pack, stationCount)
        b.themeSettingsWheelSideRow.visibility = if (arcMode) View.VISIBLE else View.GONE
        b.themeSettingsBottomReserve.visibility = if (arcMode) View.GONE else View.VISIBLE

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val isRight = arcMode && (prefs.getString(PREF_WHEEL_ARC_SIDE, "left") ?: "left").equals("right", ignoreCase = true)

        b.themeRadioWheel.wheelMode = when {
            !arcMode -> RadioWheelView.WheelMode.FULL_CIRCLE
            isRight -> RadioWheelView.WheelMode.ARC_RIGHT
            else -> RadioWheelView.WheelMode.ARC_LEFT
        }
        b.themeWheelStage.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (arcMode) {
                topToBottom = ConstraintLayout.LayoutParams.UNSET
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = lastTopInset + (6 * density).roundToInt()
            } else {
                topToTop = ConstraintLayout.LayoutParams.UNSET
                topToBottom = R.id.themeHeaderTitle
                topMargin = (6 * density).roundToInt()
            }
        }
        b.themeHeaderTitle.gravity = if (isRight) Gravity.END else Gravity.START
        val tabParams = b.themeSettingsTab.layoutParams as FrameLayout.LayoutParams
        tabParams.gravity = if (isRight) Gravity.END else Gravity.START
        b.themeSettingsTab.layoutParams = tabParams
        b.themeSettingsTab.requestLayout()
        updateSettingsCardValues()

        b.themeRadioWheel.post {
            updateWheelMetadataBounds()
        }
    }

    private fun updateWheelMetadataBounds() {
        val b = _binding ?: return
        if (b.themeWheelStage.visibility != View.VISIBLE) return
        val stageRect = b.themeRadioWheel.getMetadataStageRect()
        val rectW = stageRect.width().roundToInt()
        val rectH = stageRect.height().roundToInt()
        if (rectW <= 0 || rectH <= 0) return

        val density = resources.displayMetrics.density
        val maxTextW = (rectW - (10 * density).roundToInt()).coerceAtLeast(48)
        b.themeWheelMetaContainer.updateLayoutParams<FrameLayout.LayoutParams> {
            width = rectW
            height = rectH
            leftMargin = stageRect.left.roundToInt()
            topMargin = stageRect.top.roundToInt()
            gravity = Gravity.TOP or Gravity.START
        }

        val isCircleMode = b.themeRadioWheel.wheelMode == RadioWheelView.WheelMode.FULL_CIRCLE
        val artSize = if (isCircleMode) {
            min(rectW * 0.62f, rectH * 0.48f).roundToInt()
                .coerceIn((88 * density).roundToInt(), (124 * density).roundToInt())
        } else {
            min(rectW * 0.68f, rectH * 0.44f).roundToInt()
                .coerceIn((96 * density).roundToInt(), (128 * density).roundToInt())
        }
        b.themeWheelArt.updateLayoutParams<ViewGroup.LayoutParams> {
            width = artSize
            height = artSize
        }

        val textViews = listOf(
            b.themeWheelNowPlaying,
            b.themeWheelStation,
            b.themeWheelTitle,
            b.themeWheelArtist,
            b.themeWheelAlbum,
            b.themeWheelOffLabel
        )
        for (tv in textViews) {
            tv.maxWidth = maxTextW
        }
    }

    private fun recenterInfiniteScrollIfNeeded() {
        if (_binding == null || stripCycleWidthPx <= 0) return
        val scrollView = binding.themeStripIconScroll
        val sidePad = binding.themeStripIconRow.paddingStart
        val curX = scrollView.scrollX
        val curCycle = ((curX - sidePad).coerceAtLeast(0)) / stripCycleWidthPx
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

    private fun cancelStripProgrammaticScroll() {
        stripPendingPostRunnable?.let { r ->
            _binding?.themeStripIconScroll?.removeCallbacks(r)
        }
        stripPendingPostRunnable = null
        stripProgrammaticEndRunnable?.let { scrollHandler.removeCallbacks(it) }
        stripProgrammaticEndRunnable = null
        isProgrammaticScroll = false
    }

    private fun scrollSlotToCenter(slot: StripSlot, smooth: Boolean = true) {
        val b = _binding ?: return
        val iv = slot.imageView
        val scrollView = b.themeStripIconScroll
        stripPendingPostRunnable?.let { scrollView.removeCallbacks(it) }
        stripProgrammaticEndRunnable?.let { scrollHandler.removeCallbacks(it) }
        stripProgrammaticEndRunnable = null

        val postRunnable = Runnable {
            stripPendingPostRunnable = null
            if (_binding == null) return@Runnable
            val targetX = ((iv.left + iv.right) / 2 - (scrollView.width / 2)).coerceAtLeast(0)
            isProgrammaticScroll = true
            if (smooth) {
                scrollView.smoothScrollTo(targetX, 0)
            } else {
                scrollView.scrollTo(targetX, 0)
            }
            val endRunnable = Runnable {
                stripProgrammaticEndRunnable = null
                isProgrammaticScroll = false
                if (_binding != null && !isUserScrollingStrip) {
                    recenterInfiniteScrollIfNeeded()
                }
            }
            stripProgrammaticEndRunnable = endRunnable
            scrollHandler.postDelayed(endRunnable, 240L)
        }
        stripPendingPostRunnable = postRunnable
        scrollView.post(postRunnable)
    }

    private fun scrollIconToCenter(mediaId: String, smooth: Boolean = true) {
        if (_binding == null || mediaId.isBlank()) return
        if (!isUserScrollingStrip) {
            recenterInfiniteScrollIfNeeded()
        }
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
            pendingStripCommitKey = STRIP_OFF_COMMIT_KEY
            pendingStripCommitUntilMs = SystemClock.elapsedRealtime() + 3500L
            DynamicThemeManager.stopTuningNoise("user_stop")
            musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
            transportControls?.stop()
        } else {
            pendingStripCommitKey = clickedId
            pendingStripCommitUntilMs = SystemClock.elapsedRealtime() + 3500L
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
        val inStrip = activeThemePack?.let { isStripLayout(it) } == true
        if (currentPlaybackState.isPlaying) {
            if (inStrip) {
                pendingStripCommitKey = STRIP_OFF_COMMIT_KEY
                pendingStripCommitUntilMs = SystemClock.elapsedRealtime() + 3500L
            }
            DynamicThemeManager.stopTuningNoise("user_power_off")
            musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
            transportControls?.stop()
            if (inStrip) updateDynamicThemePlaybackUi()
        } else {
            val targetItem = loadedStations.find { it.mediaId == centeredMediaId }
                ?: loadedStations.find { it.mediaMetadata.title?.toString().equals(selectedStationTitle, ignoreCase = true) }
                ?: loadedStations.firstOrNull()
            if (targetItem != null) {
                centeredMediaId = targetItem.mediaId
                selectedStationTitle = targetItem.mediaMetadata.title?.toString() ?: ""
                lastAutoScrolledMediaId = centeredMediaId
                if (inStrip) {
                    pendingStripCommitKey = targetItem.mediaId
                    pendingStripCommitUntilMs = SystemClock.elapsedRealtime() + 3500L
                }
                scrollIconToCenter(centeredMediaId, smooth = true)
                transportControls?.playFromMediaId(targetItem.mediaId, null)
                if (inStrip) updateDynamicThemePlaybackUi()
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

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)
        prefs.registerOnSharedPreferenceChangeListener(settingsPrefListener)
        updateSettingsCardValues()

        binding.themeSettingsLoudnessRow.setOnClickListener {
            val cur = prefs.getBoolean(PREF_LOUDNESS, true)
            prefs.edit().putBoolean(PREF_LOUDNESS, !cur).apply()
            DynamicThemeManager.playClickSound(requireContext(), activeThemePack)
            updateSettingsCardValues()
        }
        binding.themeSettingsEqRow.setOnClickListener {
            val cur = prefs.getBoolean(PREF_CAR_EQ, false)
            prefs.edit().putBoolean(PREF_CAR_EQ, !cur).apply()
            DynamicThemeManager.playClickSound(requireContext(), activeThemePack)
            updateSettingsCardValues()
        }
        binding.themeSettingsWheelSideRow.setOnClickListener {
            val cur = prefs.getString(PREF_WHEEL_ARC_SIDE, "left") ?: "left"
            val next = if (cur.equals("right", ignoreCase = true)) "left" else "right"
            prefs.edit().putString(PREF_WHEEL_ARC_SIDE, next).apply()
            DynamicThemeManager.playClickSound(requireContext(), activeThemePack)
            updateSettingsCardValues()
            val pack = activeThemePack
            if (pack != null && isWheelTheme(pack)) {
                applyWheelOrientationAndStage(pack, loadedStations.size)
            }
        }
        binding.themeRadioWheel.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                updateWheelMetadataBounds()
            }
        }

        binding.themeRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioLabel.setOnClickListener { toggleRadioPower() }

        binding.themeStripIconScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelStripProgrammaticScroll()
                    stripTouchDownScrollX = binding.themeStripIconScroll.scrollX
                    isUserScrollingStrip = true
                    scrollHandler.removeCallbacks(scrollSnapRunnable)
                    recenterInfiniteScrollIfNeeded()
                    stripTouchDownScrollX = binding.themeStripIconScroll.scrollX
                }
                MotionEvent.ACTION_MOVE -> {
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
                val dragThresholdPx = (8 * resources.displayMetrics.density).roundToInt()
                val movedPastThreshold = abs(binding.themeStripIconScroll.scrollX - stripTouchDownScrollX) > dragThresholdPx
                val centerSlot = findClosestCenteredSlot()
                val slotChanged = centerSlot != null && centerSlot.mediaId != centeredMediaId
                if (movedPastThreshold || slotChanged) {
                    pendingStripCommitKey = null
                }
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
            binding.themeSettingsVolumeBar.currentBars = bars
        }
        syncBarsFromSystem()

        val onVolumeChanged: (Int, Int) -> Unit = { bars, maxBars ->
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = ((bars.toFloat() / maxBars.toFloat()) * maxSys).roundToInt().coerceIn(0, maxSys)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            binding.themeVolumeBar.currentBars = bars
            binding.themeStripVolumeBar.currentBars = bars
            binding.themeSettingsVolumeBar.currentBars = bars
        }
        binding.themeVolumeBar.onVolumeBarsChanged = onVolumeChanged
        binding.themeStripVolumeBar.onVolumeBarsChanged = onVolumeChanged
        binding.themeSettingsVolumeBar.onVolumeBarsChanged = onVolumeChanged

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
                    val wheelOffUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, "Radio Off")
                    DynamicThemeManager.loadBitmap(ctx, wheelOffUri, 256)
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

        val wheelMode = isWheelTheme(pack)
        val stripMode = isStripLayout(pack)

        if (wheelMode) {
            binding.themeNowPlayingGroup.visibility = View.GONE
            binding.themeControlsBlock.visibility = View.GONE
            binding.themeStripControlsBlock.visibility = View.GONE
            binding.themeWheelStage.visibility = View.VISIBLE
            binding.themeSettingsContainer.visibility = View.VISIBLE
        } else {
            binding.themeWheelStage.visibility = View.GONE
            binding.themeSettingsContainer.visibility = if (pack.showSettingsPanel) View.VISIBLE else View.GONE
            binding.themeControlsBlock.visibility = if (stripMode) View.GONE else View.VISIBLE
            binding.themeStripControlsBlock.visibility = if (stripMode) View.VISIBLE else View.GONE
            binding.themeHeaderTitle.gravity = Gravity.START
            // Keep grid/strip metadata slots out of View.GONE so layout height never collapses
            binding.themeNowPlayingGroup.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
            binding.themeNowPlayingLabel.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
            binding.themeSongArt.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
            binding.themeSongTitle.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
            binding.themeSongArtist.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
            binding.themeSongAlbum.visibility = if (stripMode) View.VISIBLE else View.INVISIBLE
        }

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

        if (wheelMode || pack.showSettingsPanel) {
            binding.themeSettingsTabAccent.setBackgroundColor(pack.accentColor)
            binding.themeSettingsVolumeBar.continuousMode = true
            binding.themeSettingsVolumeBar.activeBarColor = pack.accentColor
            binding.themeSettingsVolumeBar.inactiveBarColor = Color.parseColor("#334A38")
            pack.bodyTypeface?.let { tf ->
                listOf(
                    binding.themeSettingsTabLabel,
                    binding.themeSettingsVolumeLabel,
                    binding.themeSettingsLoudnessLabel,
                    binding.themeSettingsLoudnessValue,
                    binding.themeSettingsEqLabel,
                    binding.themeSettingsEqValue,
                    binding.themeSettingsWheelSideLabel,
                    binding.themeSettingsWheelSideValue
                ).forEach { it.typeface = tf }
            }
        }

        if (wheelMode) {
            val metaTf = pack.metadataTypeface ?: pack.bodyTypeface
            val strokeW = if (pack.metadataStrokeWidthDp > 0f) pack.metadataStrokeWidthDp else 2.5f
            listOf(
                binding.themeWheelNowPlaying,
                binding.themeWheelStation,
                binding.themeWheelTitle,
                binding.themeWheelArtist,
                binding.themeWheelAlbum,
                binding.themeWheelOffLabel
            ).forEach { tv ->
                metaTf?.let { tv.typeface = it }
                tv.setTextColor(Color.WHITE)
                tv.strokeColor = pack.metadataStrokeColor
                tv.strokeWidthDp = strokeW
            }
            binding.themeRadioWheel.accentColor = pack.accentColor
            applyWheelOrientationAndStage(pack, loadedStations.size)
        } else if (pack.showSettingsPanel) {
            binding.themeSettingsWheelSideRow.visibility = View.GONE
            binding.themeSettingsBottomReserve.visibility = View.GONE
            updateSettingsCardValues()
        }

        binding.themeVolumeBar.continuousMode = false
        binding.themeVolumeBar.activeBarColor = pack.volumeBarColor

        binding.themeStripVolumeBar.continuousMode = true
        binding.themeStripVolumeBar.activeBarColor = pack.volumeBarColor
        binding.themeStripVolumeBar.inactiveBarColor = Color.parseColor("#3B3B3B")

        if (!stripMode && !wheelMode) {
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

        val wheelMode = isWheelTheme(pack)
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
        wheelOffIconBitmap = null
        wheelScrubbingSlot = null

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

        val wheelOffUri = if (wheelMode) DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, "Radio Off") else null
        if (wheelMode && wheelOffUri != null) {
            wheelOffIconBitmap = DynamicThemeManager.getCachedBitmap(wheelOffUri, 256)
            if (wheelOffIconBitmap == null) {
                missingIcons = true
            }
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
                val loadedOffBmp = if (wheelMode && wheelOffUri != null) {
                    wheelOffIconBitmap ?: DynamicThemeManager.loadBitmap(ctx, wheelOffUri, 256)
                } else null
                withContext(Dispatchers.Main.immediate) {
                    if (_binding != null && isAdded) {
                        if (loadedOffBmp != null) {
                            wheelOffIconBitmap = loadedOffBmp
                        }
                        if (wheelMode) {
                            for (item in children) {
                                binding.themeRadioWheel.updateSlotIcon(item.mediaId, stationOnBitmaps[item.mediaId])
                            }
                            if (loadedOffBmp != null) {
                                binding.themeRadioWheel.updateSlotIcon(null, loadedOffBmp)
                            }
                        } else {
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
                        }
                        updateDynamicThemePlaybackUi()
                    }
                }
            }
        }

        if (wheelMode) {
            val slots = ArrayList<RadioWheelView.WheelSlot>(children.size + 1)
            for (item in children) {
                val mId = item.mediaId
                val title = item.mediaMetadata.title?.toString() ?: ""
                slots.add(RadioWheelView.WheelSlot(mediaId = mId, title = title, icon = stationOnBitmaps[mId], isOffSlot = false))
            }
            slots.add(RadioWheelView.WheelSlot(mediaId = null, title = "Radio Off", icon = wheelOffIconBitmap, isOffSlot = true))
            binding.themeRadioWheel.setSlots(slots)
            binding.themeRadioWheel.onInteractionStart = {
                isWheelInteracting = true
            }
            binding.themeRadioWheel.onInteractionEnd = {
                isWheelInteracting = false
                if (pendingWheelCommitKey == null) {
                    wheelScrubbingSlot = null
                }
                updateDynamicThemePlaybackUi()
            }
            binding.themeRadioWheel.onSlotHovered = { slot ->
                wheelScrubbingSlot = slot
                context?.let { DynamicThemeManager.playClickSound(it, pack) }
                updateDynamicThemePlaybackUi()
            }
            binding.themeRadioWheel.onSlotCommitted = { slot ->
                wheelScrubbingSlot = slot
                val transportControls = musicServiceConnection?.transportControls
                val currentActiveId = currentMetadata.mediaId ?: ""
                if (slot.isOffSlot || slot.mediaId == null) {
                    pendingWheelCommitKey = RADIO_OFF_COMMIT_KEY
                    pendingWheelCommitUntilMs = SystemClock.elapsedRealtime() + 2500L
                    DynamicThemeManager.stopTuningNoise("wheel_off")
                    musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
                    transportControls?.stop()
                } else {
                    val targetId = slot.mediaId
                    selectedStationTitle = slot.title
                    centeredMediaId = targetId
                    pendingWheelCommitKey = targetId
                    pendingWheelCommitUntilMs = SystemClock.elapsedRealtime() + 2500L
                    if (!currentPlaybackState.isPlaying || targetId != currentActiveId) {
                        transportControls?.playFromMediaId(targetId, null)
                    }
                }
                updateDynamicThemePlaybackUi()
            }
            applyWheelOrientationAndStage(pack, children.size)
        } else if (stripMode && children.isNotEmpty()) {
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

    private fun setMarqueeTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) {
            view.text = value
        }
        view.isSelected = true
    }

    private fun isTalkOrNewsBroadcast(
        stationTitle: String,
        songTitle: String,
        cleanArtist: String,
        cleanAlbum: String,
        cachedSongArt: Bitmap?,
        hideArtOnNonSong: Boolean
    ): Boolean {
        val isKnownTalkStation = Rpf7MetadataRepository.stationInfoByRpf.values.any {
            it.stationName.equals(stationTitle, ignoreCase = true) && it.isTalk
        }
        if (isKnownTalkStation || stationTitle.contains("talk", ignoreCase = true)) {
            return true
        }
        if (cleanAlbum.isBlank() && cachedSongArt == null) {
            if (cleanArtist.equals(stationTitle, ignoreCase = true) ||
                cleanArtist.contains("talk", ignoreCase = true) ||
                songTitle.contains("news", ignoreCase = true) ||
                songTitle.contains("talk show", ignoreCase = true)
            ) {
                return true
            }
            if (hideArtOnNonSong && Rpf7MetadataRepository.stationInfoByRpf.values.any {
                    it.stationName.equals(stationTitle, ignoreCase = true) && it.isContinuousMix
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun showWheelOffPreview(b: FragmentRadiolistBinding) {
        b.themeWheelNowPlaying.visibility = View.GONE
        b.themeWheelArt.visibility = View.GONE
        b.themeWheelStation.visibility = View.GONE
        b.themeWheelTitle.visibility = View.GONE
        b.themeWheelArtist.visibility = View.GONE
        b.themeWheelAlbum.visibility = View.GONE
        b.themeWheelOffLabel.visibility = View.VISIBLE
        setMarqueeTextIfChanged(b.themeWheelOffLabel, "Radio Off")
    }

    private fun showWheelStationOnlyPreview(b: FragmentRadiolistBinding, stationTitle: String) {
        b.themeWheelOffLabel.visibility = View.GONE
        b.themeWheelNowPlaying.visibility = View.GONE
        b.themeWheelArt.visibility = View.GONE
        b.themeWheelTitle.visibility = View.GONE
        b.themeWheelArtist.visibility = View.GONE
        b.themeWheelAlbum.visibility = View.GONE
        b.themeWheelStation.visibility = View.VISIBLE
        setMarqueeTextIfChanged(b.themeWheelStation, stationTitle)
    }

    private fun updateWheelPlaybackUi(
        pack: DynamicThemePack,
        effectivePlaying: Boolean,
        activeMediaId: String,
        activeStationFromMeta: String
    ) {
        val b = _binding ?: return

        val commitKey = pendingWheelCommitKey
        if (commitKey != null) {
            val committedOff = commitKey == RADIO_OFF_COMMIT_KEY
            val serviceCaughtUp = if (committedOff) {
                !effectivePlaying
            } else {
                effectivePlaying && activeMediaId == commitKey
            }
            if (serviceCaughtUp || SystemClock.elapsedRealtime() >= pendingWheelCommitUntilMs) {
                pendingWheelCommitKey = null
                if (!isWheelInteracting) {
                    wheelScrubbingSlot = null
                }
            }
        }

        val guardActive = isWheelInteracting || pendingWheelCommitKey != null
        if (!guardActive) {
            wheelScrubbingSlot = null
            val wheelHighlightId = if (effectivePlaying) activeMediaId else null
            b.themeRadioWheel.setActiveMediaId(wheelHighlightId, effectivePlaying)
        } else {
            val scrubbing = wheelScrubbingSlot
            if (scrubbing != null) {
                if (scrubbing.isOffSlot || scrubbing.mediaId == null) {
                    showWheelOffPreview(b)
                } else {
                    showWheelStationOnlyPreview(b, scrubbing.title)
                }
                return
            }
            val activePending = pendingWheelCommitKey
            if (activePending != null) {
                if (activePending == RADIO_OFF_COMMIT_KEY) {
                    showWheelOffPreview(b)
                } else {
                    val pendingTitle = loadedStations.find { it.mediaId == activePending }
                        ?.mediaMetadata?.title?.toString() ?: ""
                    showWheelStationOnlyPreview(b, pendingTitle)
                }
                return
            }
        }

        // State 1: Radio Off
        if (!effectivePlaying) {
            showWheelOffPreview(b)
            return
        }

        val stationTitle = activeStationFromMeta.ifBlank {
            loadedStations.find { it.mediaId == activeMediaId }?.mediaMetadata?.title?.toString() ?: ""
        }
        val isSongOrShow = !DynamicThemeManager.isPlayingTuningNoise() && currentMetadata.isSong
        val songTitle = currentMetadata.title?.toString() ?: ""
        val cleanArtist = currentMetadata.artist?.toString() ?: ""
        val cleanAlbum = currentMetadata.albumArtist?.toString() ?: ""

        // State 4: Ads, DJ Chatter, Weather, Station IDs
        if (!isSongOrShow || songTitle.isBlank() || songTitle.equals(stationTitle, ignoreCase = true)) {
            showWheelStationOnlyPreview(b, stationTitle)
            return
        }

        val cachedSongArt = if (cleanArtist.isNotBlank()) {
            AlbumArtDownloader.getCachedAlbumArt(requireContext(), cleanArtist, songTitle)
        } else null
        val isTalkOrNews = isTalkOrNewsBroadcast(
            stationTitle = stationTitle,
            songTitle = songTitle,
            cleanArtist = cleanArtist,
            cleanAlbum = cleanAlbum,
            cachedSongArt = cachedSongArt,
            hideArtOnNonSong = pack.hideArtOnNonSong
        )

        b.themeWheelOffLabel.visibility = View.GONE
        b.themeWheelNowPlaying.visibility = View.VISIBLE
        setMarqueeTextIfChanged(b.themeWheelNowPlaying, "NOW PLAYING")
        b.themeWheelStation.visibility = View.VISIBLE
        setMarqueeTextIfChanged(b.themeWheelStation, stationTitle)
        b.themeWheelTitle.visibility = View.VISIBLE
        setMarqueeTextIfChanged(b.themeWheelTitle, songTitle)
        if (cleanArtist.isNotBlank()) {
            b.themeWheelArtist.visibility = View.VISIBLE
            setMarqueeTextIfChanged(b.themeWheelArtist, cleanArtist)
        } else {
            b.themeWheelArtist.visibility = View.GONE
        }

        // State 3: Talk Radio Show / News
        if (isTalkOrNews) {
            b.themeWheelArt.visibility = View.GONE
            b.themeWheelAlbum.visibility = View.GONE
            return
        }

        // State 2: Music Song
        if (cleanAlbum.isNotBlank()) {
            b.themeWheelAlbum.visibility = View.VISIBLE
            setMarqueeTextIfChanged(b.themeWheelAlbum, cleanAlbum)
        } else {
            b.themeWheelAlbum.visibility = View.GONE
        }

        val artBmp = cachedSongArt ?: currentMetadata.iconBitmap ?: stationOnBitmaps[activeMediaId]
        if (artBmp != null) {
            b.themeWheelArt.setImageBitmap(artBmp)
        } else {
            b.themeWheelArt.setImageResource(R.drawable.outline_radio_24)
        }
        b.themeWheelArt.visibility = View.VISIBLE
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

        if (isWheelTheme(pack)) {
            updateWheelPlaybackUi(pack, effectivePlaying, activeMediaId, activeStationFromMeta)
            return
        }

        if (stripMode) {
            val stripCommitKey = pendingStripCommitKey
            if (stripCommitKey != null) {
                val now = SystemClock.elapsedRealtime()
                val extraStationId = sessionExtras?.getString(TracklistContract.EXTRA_STATION_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: currentPlaybackState.extras?.getString(TracklistContract.EXTRA_STATION_ID)
                        ?.takeIf { it.isNotBlank() }
                val committedOff = stripCommitKey == STRIP_OFF_COMMIT_KEY
                val serviceCaughtUp = if (committedOff) {
                    !effectivePlaying
                } else {
                    effectivePlaying &&
                        activeMediaId == stripCommitKey &&
                        (extraStationId == null || extraStationId == stripCommitKey)
                }
                if (serviceCaughtUp || now >= pendingStripCommitUntilMs) {
                    pendingStripCommitKey = null
                }
            }

            val activePendingStrip = pendingStripCommitKey
            val isPendingOff = activePendingStrip == STRIP_OFF_COMMIT_KEY
            val stripDisplayPlaying = when {
                isPendingOff -> false
                activePendingStrip != null -> true
                else -> effectivePlaying
            }
            val statusColor = if (stripDisplayPlaying) pack.activeTextColor else pack.labelColor
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.setTextColor(statusColor)

            binding.themeStripRadioLabel.setTextColor(statusColor)
            binding.themeStripRadioState.text = if (stripDisplayPlaying) "On" else "Off"
            binding.themeStripRadioState.setTextColor(statusColor)
            binding.themeStripVolumeLabel.setTextColor(statusColor)
            binding.themeStripStationLabel.setTextColor(statusColor)

            val selectedItem = when {
                isUserScrollingStrip -> loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.firstOrNull()
                isPendingOff -> loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.firstOrNull()
                activePendingStrip != null -> loadedStations.find { it.mediaId == activePendingStrip }
                    ?: loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.firstOrNull()
                activeBelongsToThisSeries -> loadedStations.find { it.mediaId == activeMediaId }
                else -> loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.firstOrNull()
            }
            val selectedMediaId = selectedItem?.mediaId ?: activePendingStrip?.takeUnless { isPendingOff } ?: ""
            val previouslyCenteredId = centeredMediaId
            if (!isUserScrollingStrip && !isPendingOff && selectedMediaId.isNotBlank()) {
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

            val highlightedId = when {
                isPendingOff -> ""
                isUserScrollingStrip -> if (effectivePlaying) centeredMediaId else ""
                activePendingStrip != null -> selectedMediaId
                effectivePlaying -> selectedMediaId
                else -> ""
            }
            updateStripIconsHighlight(highlightedId)

            if (!isUserScrollingStrip && activePendingStrip == null && selectedMediaId.isNotBlank() &&
                selectedMediaId != lastAutoScrolledMediaId &&
                selectedMediaId != previouslyCenteredId) {
                lastAutoScrolledMediaId = selectedMediaId
                scrollIconToCenter(selectedMediaId, smooth = true)
            }

            val showLivePlayingMeta = effectivePlaying &&
                !isPendingOff &&
                (activePendingStrip == null || activePendingStrip == activeMediaId)
            val isSongOrShow = showLivePlayingMeta && !DynamicThemeManager.isPlayingTuningNoise() &&
                currentMetadata.isSong

            if (showLivePlayingMeta) {
                binding.themeSongTitle.setTextColor(pack.activeTextColor)
                binding.themeSongArtist.setTextColor(pack.activeTextColor)
                binding.themeSongAlbum.setTextColor(pack.activeTextColor)

                if (isSongOrShow) {
                    val songTitle = currentMetadata.title?.toString() ?: ""
                    val cleanArtist = currentMetadata.artist?.toString() ?: ""
                    val cleanAlbum = currentMetadata.albumArtist?.toString() ?: currentMetadata.albumTitle?.toString() ?: ""

                    binding.themeSongTitle.visibility = if (songTitle.isNotBlank()) View.VISIBLE else View.INVISIBLE
                    binding.themeSongTitle.text = songTitle.uppercase()
                    binding.themeSongArtist.visibility = if (cleanArtist.isNotBlank()) View.VISIBLE else View.INVISIBLE
                    binding.themeSongArtist.text = cleanArtist.uppercase()
                    binding.themeSongAlbum.visibility = if (cleanAlbum.isNotBlank()) View.VISIBLE else View.INVISIBLE
                    binding.themeSongAlbum.text = cleanAlbum.uppercase()
                } else {
                    binding.themeSongTitle.visibility = if (displayStationTitle.isNotBlank()) View.VISIBLE else View.INVISIBLE
                    binding.themeSongTitle.text = displayStationTitle.uppercase()
                    binding.themeSongArtist.visibility = View.INVISIBLE
                    binding.themeSongArtist.text = ""
                    binding.themeSongAlbum.visibility = View.INVISIBLE
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
                binding.themeSongArt.visibility = if (!isSongOrShow && pack.hideArtOnNonSong) View.INVISIBLE else View.VISIBLE
            } else {
                val offArtBmp = stationOnBitmaps[selectedMediaId] ?: stationOffBitmaps[selectedMediaId]
                if (offArtBmp != null) {
                    binding.themeSongArt.setImageBitmap(offArtBmp)
                } else {
                    binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
                }
                binding.themeSongArt.alpha = if (activePendingStrip != null && !isPendingOff) 1.0f else 0.45f
                binding.themeSongArt.visibility = if (pack.hideArtOnNonSong) View.INVISIBLE else View.VISIBLE
                binding.themeSongTitle.visibility = View.INVISIBLE
                binding.themeSongTitle.text = ""
                binding.themeSongArtist.visibility = View.INVISIBLE
                binding.themeSongArtist.text = ""
                binding.themeSongAlbum.visibility = View.INVISIBLE
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
                    binding.themeSongArtist.text = ""
                    binding.themeSongArtist.visibility = View.INVISIBLE
                }

                if (cleanAlbum.isNotBlank()) {
                    binding.themeSongAlbum.visibility = View.VISIBLE
                    binding.themeSongAlbum.text = cleanAlbum.uppercase()
                } else {
                    binding.themeSongAlbum.text = ""
                    binding.themeSongAlbum.visibility = View.INVISIBLE
                }
            } else {
                binding.themeSongTitle.visibility = if (gridStationUpper.isNotBlank()) View.VISIBLE else View.INVISIBLE
                binding.themeSongTitle.text = gridStationUpper
                binding.themeSongArtist.text = ""
                binding.themeSongArtist.visibility = View.INVISIBLE
                binding.themeSongAlbum.text = ""
                binding.themeSongAlbum.visibility = View.INVISIBLE
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
            binding.themeSongArt.visibility = if (!isSongOrShow && pack.hideArtOnNonSong) View.INVISIBLE else View.VISIBLE
        } else {
            binding.themeNowPlayingGroup.visibility = View.INVISIBLE
            binding.themeNowPlayingLabel.visibility = View.INVISIBLE
            binding.themeSongArt.visibility = View.INVISIBLE
            binding.themeSongTitle.text = ""
            binding.themeSongTitle.visibility = View.INVISIBLE
            binding.themeSongArtist.text = ""
            binding.themeSongArtist.visibility = View.INVISIBLE
            binding.themeSongAlbum.text = ""
            binding.themeSongAlbum.visibility = View.INVISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelStripProgrammaticScroll()
        if (stationGroupId.isNotBlank()) {
            musicServiceConnection?.unsubscribe(stationGroupId, subscriptionCallback)
        }
        bgHandler.removeCallbacksAndMessages(null)
        scrollHandler.removeCallbacksAndMessages(null)
        context?.applicationContext?.let { appCtx ->
            try {
                PreferenceManager.getDefaultSharedPreferences(appCtx)
                    .unregisterOnSharedPreferenceChangeListener(settingsPrefListener)
            } catch (_: Exception) {}
        }
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
