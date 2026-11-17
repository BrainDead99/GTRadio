package com.sc.gtradio

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
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
import kotlin.math.roundToInt

class RadioList : Fragment() {
    var stationGroupId: String = ""
    private var _binding: FragmentRadiolistBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null

    private var activeThemePack: DynamicThemePack? = null
    private var loadedStations: List<MediaBrowserCompat.MediaItem> = emptyList()
    private val stationIconViews = HashMap<String, ImageView>()
    private var selectedStationTitle: String = ""
    private var currentPlaybackState: PlaybackStateCompat = EMPTY_PLAYBACK_STATE
    private var currentMetadata: MediaMetadataCompat = NOTHING_PLAYING

    private val bgHandler = Handler(Looper.getMainLooper())
    private var bgIndex = 0
    private var showingBgA = true
    private var volumeObserver: ContentObserver? = null

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

    private fun startStationWithTuningNoise(playAction: () -> Unit) {
        // Tuning noise and stream muting are now handled globally inside GTRadioMusicService
        playAction()
    }

    private fun onStationClicked(clickedItem: MediaBrowserCompat.MediaItem) {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.stationList.layoutManager = LinearLayoutManager(context)
        binding.stationList.adapter = listAdapter

        musicServiceConnection?.playbackState?.observe(viewLifecycleOwner, playbackStateObserver)
        musicServiceConnection?.nowPlaying?.observe(viewLifecycleOwner, mediaMetadataObserver)

        setupVolumeControls()

        binding.themeRadioState.setOnClickListener {
            val transportControls = musicServiceConnection?.transportControls
            if (currentPlaybackState.isPlaying) {
                DynamicThemeManager.stopTuningNoise()
                musicServiceConnection?.sendCommand("setStreamMuted", Bundle().apply { putBoolean("muted", false) })
                transportControls?.stop()
            } else {
                val firstOrSelected = loadedStations.find {
                    it.description.title?.toString().equals(selectedStationTitle, ignoreCase = true)
                } ?: loadedStations.firstOrNull()
                if (currentMetadata.description.mediaId.isNullOrBlank() && firstOrSelected != null) {
                    selectedStationTitle = firstOrSelected.description.title?.toString() ?: ""
                    startStationWithTuningNoise { transportControls?.playFromMediaId(firstOrSelected.mediaId, null) }
                } else {
                    startStationWithTuningNoise { transportControls?.play() }
                }
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
        }
        syncBarsFromSystem()

        binding.themeVolumeBar.onVolumeBarsChanged = { bars, maxBars ->
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = ((bars.toFloat() / maxBars.toFloat()) * maxSys).roundToInt().coerceIn(0, maxSys)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }

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
        val pack = DynamicThemeManager.findThemeForSeries(requireContext(), seriesName)
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

        // Apply Title & Colors
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
            binding.themeActiveStationName
        )
        pack.bodyTypeface?.let { tf ->
            bodyViews.forEach { it.typeface = tf }
        }

        binding.themeNowPlayingLabel.setTextColor(pack.activeTextColor)
        binding.themeSongTitle.setTextColor(pack.headerColor)
        binding.themeSongArtist.setTextColor(pack.activeTextColor)
        binding.themeSongAlbum.setTextColor(pack.labelColor)
        binding.themeRadioLabel.setTextColor(pack.labelColor)
        binding.themeVolumeLabel.setTextColor(pack.labelColor)
        binding.themeRadioStationLabel.setTextColor(pack.labelColor)
        binding.themeActiveStationName.setTextColor(pack.labelColor)
        binding.themeVolumeBar.activeBarColor = pack.volumeBarColor

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

        // Build 2-Row Station Icon Grid (6 top row, remaining 5 bottom row)
        binding.themeIconRow1.removeAllViews()
        binding.themeIconRow2.removeAllViews()
        stationIconViews.clear()

        val density = resources.displayMetrics.density
        val iconSizePx = (52 * density).roundToInt()
        val marginPx = (3 * density).roundToInt()

        children.forEachIndexed { idx, item ->
            val stTitle = item.description.title?.toString() ?: ""
            val normKey = DynamicThemeManager.normalizeStationKey(stTitle)
            val iconUri = pack.stationIconUris[normKey]
            val bmp: Bitmap? = DynamicThemeManager.loadBitmap(requireContext(), iconUri, 256)
                ?: item.description.iconBitmap

            val iv = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (bmp != null) {
                    setImageBitmap(bmp)
                } else {
                    setImageResource(R.drawable.outline_radio_24)
                }
                alpha = 0.28f
                setOnClickListener { onStationClicked(item) }
            }

            if (item.mediaId != null) {
                stationIconViews[item.mediaId!!] = iv
            }
            if (idx < 6) {
                binding.themeIconRow1.addView(iv)
            } else {
                binding.themeIconRow2.addView(iv)
            }
        }

        if (selectedStationTitle.isBlank()) {
            selectedStationTitle = children.firstOrNull()?.description?.title?.toString() ?: ""
        }
        updateDynamicThemePlaybackUi()
    }

    private fun updateDynamicThemePlaybackUi() {
        val pack = activeThemePack ?: return
        if (_binding == null) return

        val isPlaying = currentPlaybackState.isPlaying
        binding.themeRadioState.text = if (isPlaying) "ON" else "OFF"
        binding.themeRadioState.setTextColor(if (isPlaying) pack.activeTextColor else pack.labelColor)

        val activeMediaId = currentMetadata.description.mediaId ?: ""
        val activeStationFromMeta = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_WRITER)
            ?: loadedStations.find { it.mediaId == activeMediaId }?.description?.title?.toString()
            ?: selectedStationTitle

        binding.themeActiveStationName.text = activeStationFromMeta.uppercase()

        for ((mediaId, iv) in stationIconViews) {
            val isThisActive = isPlaying && mediaId == activeMediaId
            iv.alpha = if (isThisActive) 1.0f else 0.28f
        }

        val isSongOrShow = isPlaying && !DynamicThemeManager.isPlayingTuningNoise() && currentMetadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER) == 1L
        if (isSongOrShow && activeMediaId.isNotBlank()) {
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
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
