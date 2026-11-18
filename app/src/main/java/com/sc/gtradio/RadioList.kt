package com.sc.gtradio

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
    private val stationOnBitmaps = HashMap<String, Bitmap?>()
    private val stationOffBitmaps = HashMap<String, Bitmap?>()
    private var lastAutoScrolledMediaId: String = ""

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

    private fun isGta4Style(pack: DynamicThemePack): Boolean {
        return pack.layoutStyle.equals("gta4", ignoreCase = true) || pack.stationOffIconUris.isNotEmpty()
    }

    private fun startStationWithTuningNoise(playAction: () -> Unit) {
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

    private fun toggleRadioPower() {
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

        binding.themeRadioState.setOnClickListener { toggleRadioPower() }
        binding.themeGta4RadioState.setOnClickListener { toggleRadioPower() }
    }

    private fun setupVolumeControls() {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun syncBarsFromSystem() {
            if (_binding == null) return
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val bars = ((curSys.toFloat() / maxSys.toFloat()) * binding.themeVolumeBar.maxBars).roundToInt()
            binding.themeVolumeBar.currentBars = bars
            binding.themeGta4VolumeBar.currentBars = bars
        }
        syncBarsFromSystem()

        val onVolumeChanged: (Int, Int) -> Unit = { bars, maxBars ->
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = ((bars.toFloat() / maxBars.toFloat()) * maxSys).roundToInt().coerceIn(0, maxSys)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            binding.themeVolumeBar.currentBars = bars
            binding.themeGta4VolumeBar.currentBars = bars
        }
        binding.themeVolumeBar.onVolumeBarsChanged = onVolumeChanged
        binding.themeGta4VolumeBar.onVolumeBarsChanged = onVolumeChanged

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

        val gta4Mode = isGta4Style(pack)
        binding.themeControlsBlock.visibility = if (gta4Mode) View.GONE else View.VISIBLE
        binding.themeGta4ControlsBlock.visibility = if (gta4Mode) View.VISIBLE else View.GONE

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
            binding.themeGta4RadioLabel,
            binding.themeGta4RadioState,
            binding.themeGta4VolumeLabel,
            binding.themeGta4StationLabel,
            binding.themeGta4ActiveStationName
        )
        pack.bodyTypeface?.let { tf ->
            bodyViews.forEach { it.typeface = tf }
        }

        binding.themeVolumeBar.continuousMode = false
        binding.themeVolumeBar.activeBarColor = pack.volumeBarColor

        binding.themeGta4VolumeBar.continuousMode = true
        binding.themeGta4VolumeBar.activeBarColor = pack.volumeBarColor
        binding.themeGta4VolumeBar.inactiveBarColor = Color.parseColor("#3B3B3B")

        if (!gta4Mode) {
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
        binding.themeGta4IconRow.removeAllViews()
        stationIconViews.clear()
        stationOnBitmaps.clear()
        stationOffBitmaps.clear()

        val density = resources.displayMetrics.density
        val iconSizePx = ((if (gta4Mode) 64 else 52) * density).roundToInt()
        val marginPx = ((if (gta4Mode) 4 else 3) * density).roundToInt()

        children.forEachIndexed { idx, item ->
            val stTitle = item.description.title?.toString() ?: ""
            val normKey = DynamicThemeManager.normalizeStationKey(stTitle)
            val onUri = pack.stationIconUris[normKey]
            val offUri = pack.stationOffIconUris[normKey]

            val onBmp: Bitmap? = DynamicThemeManager.loadBitmap(requireContext(), onUri, 256)
                ?: item.description.iconBitmap
            val offBmp: Bitmap? = DynamicThemeManager.loadBitmap(requireContext(), offUri, 256)
                ?: onBmp

            val iv = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                val initialBmp = if (gta4Mode) offBmp else onBmp
                if (initialBmp != null) {
                    setImageBitmap(initialBmp)
                } else {
                    setImageResource(R.drawable.outline_radio_24)
                }
                alpha = if (gta4Mode && offUri != null) 1.0f else 0.28f
                setOnClickListener { onStationClicked(item) }
            }

            val mId = item.mediaId
            if (mId != null) {
                stationIconViews[mId] = iv
                stationOnBitmaps[mId] = onBmp
                stationOffBitmaps[mId] = offBmp
            }

            if (gta4Mode) {
                binding.themeGta4IconRow.addView(iv)
            } else {
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
        updateDynamicThemePlaybackUi()
    }

    private fun updateDynamicThemePlaybackUi() {
        val pack = activeThemePack ?: return
        if (_binding == null) return

        val gta4Mode = isGta4Style(pack)
        val isPlaying = currentPlaybackState.isPlaying
        val activeMediaId = currentMetadata.description.mediaId ?: ""
        val activeStationFromMeta = currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_WRITER)
            ?: loadedStations.find { it.mediaId == activeMediaId }?.description?.title?.toString()
            ?: selectedStationTitle

        if (gta4Mode) {
            val statusColor = if (isPlaying) pack.activeTextColor else pack.labelColor
            binding.themeNowPlayingGroup.visibility = View.VISIBLE
            binding.themeNowPlayingLabel.setTextColor(statusColor)

            binding.themeGta4RadioLabel.setTextColor(statusColor)
            binding.themeGta4RadioState.text = if (isPlaying) "On" else "Off"
            binding.themeGta4RadioState.setTextColor(statusColor)
            binding.themeGta4VolumeLabel.setTextColor(statusColor)
            binding.themeGta4StationLabel.setTextColor(statusColor)
            binding.themeGta4ActiveStationName.text = activeStationFromMeta
            binding.themeGta4ActiveStationName.setTextColor(statusColor)

            val selectedItem = loadedStations.find { it.mediaId == activeMediaId }
                ?: loadedStations.find { it.description.title?.toString().equals(activeStationFromMeta, ignoreCase = true) }
                ?: loadedStations.firstOrNull()
            val selectedMediaId = selectedItem?.mediaId ?: ""

            for ((mediaId, iv) in stationIconViews) {
                val isThisActive = isPlaying && mediaId == activeMediaId
                val bmp = if (isThisActive) stationOnBitmaps[mediaId] else stationOffBitmaps[mediaId]
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                }
                iv.alpha = if (isThisActive || pack.stationOffIconUris.isNotEmpty()) 1.0f else 0.28f
            }

            if (selectedMediaId.isNotBlank() && selectedMediaId != lastAutoScrolledMediaId) {
                lastAutoScrolledMediaId = selectedMediaId
                stationIconViews[selectedMediaId]?.let { targetIv ->
                    binding.themeGta4IconScroll.post {
                        if (_binding != null) {
                            val scrollX = (targetIv.left - (binding.themeGta4IconScroll.width / 2) + (targetIv.width / 2)).coerceAtLeast(0)
                            binding.themeGta4IconScroll.smoothScrollTo(scrollX, 0)
                        }
                    }
                }
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
                    binding.themeSongTitle.text = activeStationFromMeta.uppercase()
                    binding.themeSongArtist.text = ""
                    binding.themeSongAlbum.visibility = View.VISIBLE
                    binding.themeSongAlbum.text = ""
                }

                val artBmp = currentMetadata.description.iconBitmap ?: stationOnBitmaps[selectedMediaId]
                if (artBmp != null) {
                    binding.themeSongArt.setImageBitmap(artBmp)
                } else {
                    binding.themeSongArt.setImageResource(R.drawable.outline_radio_24)
                }
                binding.themeSongArt.alpha = 1.0f
            } else {
                // Radio Off state: show dimmed station icon in NOW PLAYING box and clear text without shifting layout
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

        // Standard GTA San Andreas Dynamic Theme UI
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
