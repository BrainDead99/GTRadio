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
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.DynamicThemePack
import com.sc.gtradio.databinding.FragmentRadiolistBinding
import com.sc.gtradio.databinding.FragmentStationitemBinding
import com.sc.gtradio.media.*
import com.sc.gtradio.utils.InjectorUtils
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class RadioList : Fragment() {
    var stationGroupId: String = ""
    private var _binding: FragmentRadiolistBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null

    private data class StripSlot(
        val item: MediaBrowserCompat.MediaItem,
        val mediaId: String,
        val imageView: ImageView
    )

    companion object {
        private const val REPEAT_CYCLES = 11
        private const val MIDDLE_CYCLE = REPEAT_CYCLES / 2
    }

    private var activeThemePack: DynamicThemePack? = null
    private var loadedStations: List<MediaBrowserCompat.MediaItem> = emptyList()
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
    private var isUserScrollingStrip: Boolean = false
    private var isProgrammaticScroll: Boolean = false

    private var currentPlaybackState: PlaybackStateCompat = EMPTY_PLAYBACK_STATE
    private var currentMetadata: MediaMetadataCompat = NOTHING_PLAYING

    private val bgHandler = Handler(Looper.getMainLooper())
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var bgIndex = 0
    private var showingBgA = true
    private var volumeObserver: ContentObserver? = null

    private val scrollSnapRunnable = Runnable {
        if (_binding == null) return@Runnable
        val pack = activeThemePack ?: return@Runnable
        if (!isStripLayout(pack)) return@Runnable

        isUserScrollingStrip = false
        recenterInfiniteScrollIfNeeded()

        val slot = findClosestCenteredSlot() ?: return@Runnable
        val targetId = slot.mediaId
        centeredMediaId = targetId
        selectedStationTitle = slot.item.description.title?.toString() ?: ""

        scrollSlotToCenter(slot, smooth = true)

        val nowPlayingId = currentMetadata.description.mediaId ?: ""
        if (!currentPlaybackState.isPlaying || targetId != nowPlayingId) {
            lastAutoScrolledMediaId = targetId
            startStationWithTuningNoise {
                musicServiceConnection?.transportControls?.playFromMediaId(targetId, null)
            }
        }
        updateDynamicThemePlaybackUi()
    }

    private val bgCycleRunnable = object : Runnable {
        override fun run() {
            val pack = activeThemePack ?: return
            if (_binding == null) return
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val bgList = if (isLandscape && pack.landscapeBgUris.isNotEmpty()) pack.landscapeBgUris else pack.portraitBgUris
            if (bgList.size > 1) {
                bgIndex = (bgIndex + 1) % bgList.size
                val nextBmp = DynamicThemeManager.loadBitmap(requireContext(), bgList[bgIndex], 1440)
                if (nextBmp != null) {
                    val fadeDur = pack.bgFadeMs
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
                bgHandler.postDelayed(this, pack.bgIntervalMs)
            }
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowserCompat.MediaItem>) {
            if (parentId == stationGroupId) {
                loadedStations = children
                listAdapter.submitList(children)
                setupDynamicThemeIfAvailable(children)
            }
        }
    }

    private val playbackStateObserver = Observer<PlaybackStateCompat> { state ->
        currentPlaybackState = state ?: EMPTY_PLAYBACK_STATE
        updateDynamicThemePlaybackUi()
    }

    private val mediaMetadataObserver = Observer<MediaMetadataCompat> { meta ->
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

    private fun startStationWithTuningNoise(playAction: () -> Unit) {
        playAction()
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
        val activeMediaId = currentMetadata.description.mediaId ?: ""
        val currentCenterId = centeredMediaId.ifBlank { activeMediaId }

        scrollHandler.removeCallbacks(scrollSnapRunnable)
        isUserScrollingStrip = false

        if (clickedId == currentCenterId || clickedId == activeMediaId) {
            centeredMediaId = clickedId
            selectedStationTitle = clickedItem.description.title?.toString() ?: ""
            scrollSlotToCenter(slot, smooth = true)
            if (currentPlaybackState.isPlaying && clickedId == activeMediaId) {
                DynamicThemeManager.stopTuningNoise()
                musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
                transportControls?.stop()
            } else {
                lastAutoScrolledMediaId = clickedId
                startStationWithTuningNoise { transportControls?.playFromMediaId(clickedId, null) }
            }
        } else {
            centeredMediaId = clickedId
            selectedStationTitle = clickedItem.description.title?.toString() ?: ""
            lastAutoScrolledMediaId = clickedId
            scrollSlotToCenter(slot, smooth = true)
            startStationWithTuningNoise { transportControls?.playFromMediaId(clickedId, null) }
        }
        updateDynamicThemePlaybackUi()
    }

    private fun onStationClicked(clickedItem: MediaBrowserCompat.MediaItem) {
        val pack = activeThemePack
        if (pack != null && isStripLayout(pack)) {
            val slot = findClosestSlotForMediaId(clickedItem.mediaId ?: "")
            if (slot != null) {
                onStripSlotTapped(slot)
                return
            }
        }

        val nowPlaying = musicServiceConnection?.nowPlaying?.value
        val transportControls = musicServiceConnection?.transportControls
        selectedStationTitle = clickedItem.description.title?.toString() ?: ""

        if (clickedItem.mediaId == nowPlaying?.description?.mediaId) {
            musicServiceConnection?.playbackState?.value?.let { state ->
                if (state.isPlaying) {
                    DynamicThemeManager.stopTuningNoise()
                    musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
                    transportControls?.stop()
                } else {
                    startStationWithTuningNoise { transportControls?.play() }
                }
            }
        } else {
            startStationWithTuningNoise { transportControls?.playFromMediaId(clickedItem.mediaId, null) }
        }
        updateDynamicThemePlaybackUi()
    }

    private fun toggleRadioPower() {
        val transportControls = musicServiceConnection?.transportControls
        if (currentPlaybackState.isPlaying) {
            DynamicThemeManager.stopTuningNoise()
            musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
            transportControls?.stop()
        } else {
            val targetItem = loadedStations.find { it.mediaId == centeredMediaId }
                ?: loadedStations.find { it.description.title?.toString().equals(selectedStationTitle, ignoreCase = true) }
                ?: loadedStations.firstOrNull()
            if (targetItem != null) {
                centeredMediaId = targetItem.mediaId ?: ""
                selectedStationTitle = targetItem.description.title?.toString() ?: ""
                lastAutoScrolledMediaId = centeredMediaId
                scrollIconToCenter(centeredMediaId, smooth = true)
                startStationWithTuningNoise { transportControls?.playFromMediaId(targetItem.mediaId, null) }
            } else {
                startStationWithTuningNoise { transportControls?.play() }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        stationGroupId = arguments?.getString("stationGroupId") ?: ""
        if (musicServiceConnection == null) {
            musicServiceConnection = InjectorUtils.provideMusicServiceConnection(requireContext()).also {
                it.subscribe(stationGroupId, subscriptionCallback)
            }
        }
        _binding = FragmentRadiolistBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.stationList.layoutManager = LinearLayoutManager(context)
        binding.stationList.adapter = listAdapter

        musicServiceConnection?.playbackState?.observe(viewLifecycleOwner, playbackStateObserver)
        musicServiceConnection?.nowPlaying?.observe(viewLifecycleOwner, mediaMetadataObserver)

        setupVolumeControls()

        binding.themeRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeStripRadioLabel.setOnClickListener { toggleRadioPower() }

        binding.themeStripIconScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isUserScrollingStrip = true
                    scrollHandler.removeCallbacks(scrollSnapRunnable)
                    recenterInfiniteScrollIfNeeded()
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
                val centerSlot = findClosestCenteredSlot()
                if (centerSlot != null) {
                    val cId = centerSlot.mediaId
                    val cTitle = centerSlot.item.description.title?.toString() ?: ""
                    if (cId != centeredMediaId) {
                        centeredMediaId = cId
                        selectedStationTitle = cTitle
                        binding.themeStripActiveStationName.text = cTitle
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

    private fun setupDynamicThemeIfAvailable(children: List<MediaBrowserCompat.MediaItem>) {
        if (_binding == null) return
        val seriesName = children.firstOrNull()?.description?.subtitle?.toString() ?: ""
        if (seriesName.isBlank() || !DynamicThemeManager.isDynamicThemeEnabled(requireContext())) {
            applyDynamicTheme(null, children)
            return
        }

        val cachedPack = DynamicThemeManager.getCachedThemeForSeries(seriesName)
        if (cachedPack != null) {
            applyDynamicTheme(cachedPack, children)
        } else {
            val ctx = context?.applicationContext ?: return
            Executors.newSingleThreadExecutor().submit {
                val pack = DynamicThemeManager.findThemeForSeries(ctx, seriesName)
                if (pack != null) {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val bgList = if (isLandscape && pack.landscapeBgUris.isNotEmpty()) pack.landscapeBgUris else pack.portraitBgUris
                    if (bgList.isNotEmpty()) {
                        DynamicThemeManager.loadBitmap(ctx, bgList[0], 1440)
                    }
                    for (item in children) {
                        val stTitle = item.description.title?.toString() ?: ""
                        val onUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, stTitle)
                        val offUri = DynamicThemeManager.resolveStationIconUri(pack.stationOffIconUris, stTitle)
                        DynamicThemeManager.loadBitmap(ctx, onUri, 256)
                        DynamicThemeManager.loadBitmap(ctx, offUri, 256)
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    if (_binding != null && isAdded) {
                        applyDynamicTheme(pack, children)
                    }
                }
            }
        }
    }

    private fun applyDynamicTheme(pack: DynamicThemePack?, children: List<MediaBrowserCompat.MediaItem>) {
        if (_binding == null) return
        activeThemePack = pack

        if (pack == null) {
            binding.dynamicThemeRoot.visibility = View.GONE
            binding.defaultRadioContainer.visibility = View.VISIBLE
            (activity as? MainActivity)?.setFullscreenThemeMode(false)
            return
        }

        binding.defaultRadioContainer.visibility = View.GONE
        binding.dynamicThemeRoot.visibility = View.VISIBLE
        (activity as? MainActivity)?.setFullscreenThemeMode(true)

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
            DynamicThemeManager.loadBitmap(requireContext(), bgList[0], 1440)?.let {
                binding.themeBgA.setImageBitmap(it)
            }
            if (bgList.size > 1) {
                bgHandler.postDelayed(bgCycleRunnable, pack.bgIntervalMs)
            }
        }

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

        for (item in children) {
            val mId = item.mediaId ?: continue
            val stTitle = item.description.title?.toString() ?: ""
            val onUri = DynamicThemeManager.resolveStationIconUri(pack.stationIconUris, stTitle)
            val offUri = DynamicThemeManager.resolveStationIconUri(pack.stationOffIconUris, stTitle)

            val onBmp: Bitmap? = DynamicThemeManager.loadBitmap(requireContext(), onUri, 256)
                ?: item.description.iconBitmap
            val offBmp: Bitmap? = DynamicThemeManager.loadBitmap(requireContext(), offUri, 256)
                ?: onBmp

            stationOnBitmaps[mId] = onBmp
            stationOffBitmaps[mId] = offBmp
        }

        if (stripMode && children.isNotEmpty()) {
            val hasOffIcons = pack.stationOffIconUris.isNotEmpty()
            for (cycle in 0 until REPEAT_CYCLES) {
                for (item in children) {
                    val mId = item.mediaId ?: continue
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
                    currentMetadata.description.mediaId ?: children.firstOrNull()?.mediaId ?: ""
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
                val mId = item.mediaId ?: return@forEachIndexed
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
            selectedStationTitle = children.firstOrNull()?.description?.title?.toString() ?: ""
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
        val activeMediaId = currentMetadata.description.mediaId ?: ""
        val activeStationFromMeta = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_WRITER)
            ?: loadedStations.find { it.mediaId == activeMediaId }?.description?.title?.toString()
            ?: loadedStations.find { it.mediaId == centeredMediaId }?.description?.title?.toString()
            ?: selectedStationTitle

        if (stripMode) {
            val statusColor = if (isPlaying) pack.activeTextColor else pack.labelColor
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.setTextColor(statusColor)

            binding.themeStripRadioLabel.setTextColor(statusColor)
            binding.themeStripRadioState.text = if (isPlaying) "On" else "Off"
            binding.themeStripRadioState.setTextColor(statusColor)
            binding.themeStripVolumeLabel.setTextColor(statusColor)
            binding.themeStripStationLabel.setTextColor(statusColor)

            val selectedItem = if (!isUserScrollingStrip && activeMediaId.isNotBlank() && loadedStations.any { it.mediaId == activeMediaId }) {
                loadedStations.find { it.mediaId == activeMediaId }
            } else {
                loadedStations.find { it.mediaId == centeredMediaId }
                    ?: loadedStations.find { it.description.title?.toString().equals(activeStationFromMeta, ignoreCase = true) }
                    ?: loadedStations.firstOrNull()
            }
            val selectedMediaId = selectedItem?.mediaId ?: ""
            if (!isUserScrollingStrip && selectedMediaId.isNotBlank()) {
                centeredMediaId = selectedMediaId
            }

            val displayStationTitle = if (isUserScrollingStrip) {
                loadedStations.find { it.mediaId == centeredMediaId }?.description?.title?.toString() ?: activeStationFromMeta
            } else {
                selectedItem?.description?.title?.toString() ?: activeStationFromMeta
            }
            binding.themeStripActiveStationName.text = displayStationTitle
            binding.themeStripActiveStationName.setTextColor(statusColor)

            val highlightedId = if (isPlaying) (if (isUserScrollingStrip) centeredMediaId else activeMediaId) else ""
            updateStripIconsHighlight(highlightedId)

            if (!isUserScrollingStrip && selectedMediaId.isNotBlank() && selectedMediaId != lastAutoScrolledMediaId) {
                lastAutoScrolledMediaId = selectedMediaId
                scrollIconToCenter(selectedMediaId, smooth = true)
            }

            val isSongOrShow = isPlaying && !DynamicThemeManager.isPlayingTuningNoise() &&
                currentMetadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER) == 1L

            if (isPlaying) {
                binding.themeSongTitle.setTextColor(pack.activeTextColor)
                binding.themeSongArtist.setTextColor(pack.activeTextColor)
                binding.themeSongAlbum.setTextColor(pack.activeTextColor)

                if (isSongOrShow && activeMediaId.isNotBlank()) {
                    val songTitle = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
                    val cleanArtist = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
                    val cleanAlbum = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST) ?: ""

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

                val artBmp = stationOnBitmaps[selectedMediaId] ?: currentMetadata.description.iconBitmap
                val songArtBmp = if (isSongOrShow && currentMetadata.description.iconBitmap != null) {
                    currentMetadata.description.iconBitmap
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
        binding.themeRadioState.text = if (isPlaying) "ON" else "OFF"
        binding.themeRadioState.setTextColor(if (isPlaying) pack.activeTextColor else pack.labelColor)
        binding.themeActiveStationName.text = activeStationFromMeta.uppercase()

        for ((mediaId, iv) in stationIconViews) {
            val isThisActive = isPlaying && mediaId == activeMediaId
            iv.alpha = if (isThisActive) 1.0f else 0.28f
        }

        val isSongOrShow = isPlaying && !DynamicThemeManager.isPlayingTuningNoise() &&
            currentMetadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER) == 1L
        if (isSongOrShow && activeMediaId.isNotBlank()) {
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeSongArt.alpha = 1.0f
            val songTitle = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
            val cleanArtist = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
            val cleanAlbum = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST) ?: ""

            binding.themeSongTitle.text = songTitle.uppercase()
            binding.themeSongArtist.text = cleanArtist.uppercase()

            if (cleanAlbum.isNotBlank()) {
                binding.themeSongAlbum.visibility = View.VISIBLE
                binding.themeSongAlbum.text = cleanAlbum.uppercase()
            } else {
                binding.themeSongAlbum.visibility = View.INVISIBLE
            }

            val artBmp = currentMetadata.description.iconBitmap
            if (artBmp != null) {
                binding.themeSongArt.setImageBitmap(artBmp)
            } else {
                binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
            }
        } else {
            binding.themeNowPlayingGroup.visibility = View.INVISIBLE
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
        (activity as? MainActivity)?.setFullscreenThemeMode(false)
        _binding = null
    }
}

class MediaItemListAdapter(
    private val itemClickedListener: (MediaBrowserCompat.MediaItem) -> Unit
) : ListAdapter<MediaBrowserCompat.MediaItem, MediaViewHolder>(MediaItemDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentStationitemBinding.inflate(inflater, parent, false)
        return MediaViewHolder(binding, itemClickedListener)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int, payloads: MutableList<Any>) {
        val mediaItem = getItem(position)
        if (payloads.isEmpty()) {
            holder.item = mediaItem
            holder.titleView.text = mediaItem.description.title
            val bmp = mediaItem.description.iconBitmap
            if (bmp != null) {
                holder.albumArt.setImageBitmap(bmp)
            } else {
                holder.albumArt.setImageResource(R.drawable.outline_radio_24)
            }
        }
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }
}

class MediaViewHolder(
    binding: FragmentStationitemBinding,
    itemClickedListener: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    val titleView: TextView = binding.title
    val albumArt: ImageView = binding.albumArt
    var item: MediaBrowserCompat.MediaItem? = null

    init {
        binding.root.setOnClickListener { item?.let { itemClickedListener(it) } }
    }
}

val MediaItemDiffCallback = object : DiffUtil.ItemCallback<MediaBrowserCompat.MediaItem>() {
    override fun areItemsTheSame(oldItem: MediaBrowserCompat.MediaItem, newItem: MediaBrowserCompat.MediaItem): Boolean =
        oldItem.mediaId == newItem.mediaId

    override fun areContentsTheSame(oldItem: MediaBrowserCompat.MediaItem, newItem: MediaBrowserCompat.MediaItem) =
        oldItem.mediaId == newItem.mediaId

    override fun getChangePayload(oldItem: MediaBrowserCompat.MediaItem, newItem: MediaBrowserCompat.MediaItem): Nothing? = null
}
