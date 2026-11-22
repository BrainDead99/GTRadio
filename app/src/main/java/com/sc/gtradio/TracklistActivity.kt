package com.sc.gtradio

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sc.gtradio.databinding.ActivityTracklistBinding
import com.sc.gtradio.databinding.ItemTracklistRowBinding
import com.sc.gtradio.media.*
import com.sc.gtradio.media.stations.AlbumArtDownloader
import com.sc.gtradio.media.stations.TracklistContract
import com.sc.gtradio.utils.InjectorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class TracklistActivity : AppCompatActivity() {

    enum class NavLevel {
        SERIES,
        STATIONS,
        SONGS
    }

    private lateinit var binding: ActivityTracklistBinding
    private lateinit var musicServiceConnection: MusicServiceConnection
    private var tickerJob: Job? = null

    private var currentLevel = NavLevel.SERIES
    private var currentGroupId: String? = null
    private var currentGroupName: String? = null
    private var currentStationId: String? = null
    private var currentStationName: String? = null
    private var currentSubscribedId: String? = null

    private val currentItems = mutableListOf<MediaItem>()
    private lateinit var adapter: TracklistAdapter

    private var currentPlaybackState: GTRadioPlaybackState = EMPTY_PLAYBACK_STATE
    private var currentNowPlaying: MediaMetadata = NOTHING_PLAYING
    private var isUserSeeking = false
    private var isActivityResumed = false
    private var lastSeekRequestTimeMs: Long = 0L

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            if (parentId != currentSubscribedId) return
            binding.progressBar.visibility = View.GONE
            currentItems.clear()
            currentItems.addAll(children)
            adapter.notifyDataSetChanged()

            if (children.isEmpty()) {
                val emptyText = when (currentLevel) {
                    NavLevel.SERIES -> getString(R.string.tracklist_empty_series)
                    NavLevel.STATIONS -> getString(R.string.tracklist_empty_stations)
                    NavLevel.SONGS -> getString(R.string.tracklist_empty_songs)
                }
                binding.emptyView.text = emptyText
                binding.emptyView.visibility = View.VISIBLE
            } else {
                binding.emptyView.visibility = View.GONE
            }
        }

        override fun onError(parentId: String) {
            if (parentId != currentSubscribedId) return
            binding.progressBar.visibility = View.GONE
            val emptyText = when (currentLevel) {
                NavLevel.SERIES -> getString(R.string.tracklist_empty_series)
                NavLevel.STATIONS -> getString(R.string.tracklist_empty_stations)
                NavLevel.SONGS -> getString(R.string.tracklist_empty_songs)
            }
            binding.emptyView.text = emptyText
            binding.emptyView.visibility = View.VISIBLE
        }
    }

    private var lastActiveSongMediaId: String? = null
    private var lastBoundMediaId: String? = null
    private var lastBoundTitle: CharSequence? = null
    private var lastBoundSubtitle: CharSequence? = null
    private var lastBoundArtHash: Int? = null

    private val connectionObserver = Observer<Boolean> { connected ->
        if (connected == true && currentSubscribedId == null) {
            navigateTo(NavLevel.SERIES)
        }
    }

    private val playbackStateObserver = Observer<GTRadioPlaybackState> { state ->
        currentPlaybackState = state ?: EMPTY_PLAYBACK_STATE
        updatePlaybackState(currentPlaybackState)
    }

    private val nowPlayingObserver = Observer<MediaMetadata> { metadata ->
        currentNowPlaying = metadata ?: NOTHING_PLAYING
        updateNowPlaying(currentNowPlaying)
        val newMediaId = currentNowPlaying.mediaId
        if (newMediaId != lastActiveSongMediaId) {
            val oldId = lastActiveSongMediaId
            lastActiveSongMediaId = newMediaId
            if (currentLevel == NavLevel.SONGS) {
                if (!oldId.isNullOrBlank()) {
                    val oldIdx = currentItems.indexOfFirst { it.mediaId == oldId }
                    if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
                }
                if (!newMediaId.isNullOrBlank()) {
                    val newIdx = currentItems.indexOfFirst { it.mediaId == newMediaId }
                    if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
                }
            }
        }
    }

    private val sessionExtrasObserver = Observer<Bundle?> {
        updateDurationAndPosition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlbumArtDownloader.init(applicationContext)

        binding = ActivityTracklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_tracklist)

        adapter = TracklistAdapter()
        binding.tracklistRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tracklistRecyclerView.adapter = adapter

        setupPlayerControls()

        musicServiceConnection = InjectorUtils.provideMusicServiceConnection(this)
        musicServiceConnection.isConnected.observe(this, connectionObserver)
        musicServiceConnection.playbackState.observe(this, playbackStateObserver)
        musicServiceConnection.nowPlaying.observe(this, nowPlayingObserver)
        musicServiceConnection.sessionExtras.observe(this, sessionExtrasObserver)

        if (musicServiceConnection.isConnected.value == true) {
            navigateTo(NavLevel.SERIES)
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        startTicker()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        stopTicker()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        currentSubscribedId?.let { musicServiceConnection.unsubscribe(it, subscriptionCallback) }
    }

    private fun startTicker() {
        stopTicker()
        if (currentPlaybackState.isPlaying) {
            tickerJob = lifecycleScope.launch {
                while (isActive && isActivityResumed && currentPlaybackState.isPlaying) {
                    updateDurationAndPosition()
                    delay(500L)
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun navigateTo(level: NavLevel, id: String? = null, name: String? = null) {
        currentSubscribedId?.let { musicServiceConnection.unsubscribe(it, subscriptionCallback) }

        when (level) {
            NavLevel.SERIES -> {
                currentLevel = NavLevel.SERIES
                currentGroupId = null
                currentGroupName = null
                currentStationId = null
                currentStationName = null
                supportActionBar?.title = getString(R.string.title_activity_tracklist)
                currentSubscribedId = TracklistContract.ROOT_ID
            }
            NavLevel.STATIONS -> {
                currentLevel = NavLevel.STATIONS
                currentGroupId = id
                currentGroupName = name
                currentStationId = null
                currentStationName = null
                supportActionBar?.title = name ?: getString(R.string.title_activity_tracklist)
                currentSubscribedId = TracklistContract.GROUP_PREFIX + (id ?: "")
            }
            NavLevel.SONGS -> {
                currentLevel = NavLevel.SONGS
                currentStationId = id
                currentStationName = name
                supportActionBar?.title = name ?: currentGroupName ?: getString(R.string.title_activity_tracklist)
                currentSubscribedId = TracklistContract.STATION_PREFIX + (id ?: "")
            }
        }

        currentItems.clear()
        adapter.notifyDataSetChanged()
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        currentSubscribedId?.let { subId ->
            musicServiceConnection.subscribe(subId, subscriptionCallback)
        }
    }

    private fun handleBackPress(): Boolean {
        return when (currentLevel) {
            NavLevel.SONGS -> {
                navigateTo(NavLevel.STATIONS, currentGroupId, currentGroupName)
                true
            }
            NavLevel.STATIONS -> {
                navigateTo(NavLevel.SERIES)
                true
            }
            NavLevel.SERIES -> {
                finish()
                true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBackPress()) {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleBackPress()
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return handleBackPress() || super.onSupportNavigateUp()
    }

    private fun setupPlayerControls() {
        binding.playerTrackTitle.isSelected = true

        binding.playPauseButton.setOnClickListener {
            if (currentPlaybackState.isPlaying) {
                musicServiceConnection.transportControls.pause()
            } else {
                val curMediaId = currentNowPlaying.mediaId
                if (!curMediaId.isNullOrBlank()) {
                    musicServiceConnection.transportControls.play()
                } else if (currentLevel == NavLevel.SONGS && currentItems.isNotEmpty()) {
                    currentItems.firstOrNull()?.mediaId?.let { firstId ->
                        musicServiceConnection.transportControls.playFromMediaId(firstId, null)
                    }
                }
            }
        }

        binding.prevButton.setOnClickListener {
            musicServiceConnection.transportControls.skipToPrevious()
        }

        binding.nextButton.setOnClickListener {
            musicServiceConnection.transportControls.skipToNext()
        }

        binding.shuffleButton.setOnClickListener {
            musicServiceConnection.transportControls.sendCustomAction(TracklistContract.ACTION_TOGGLE_SHUFFLE, null)
        }

        binding.repeatButton.setOnClickListener {
            musicServiceConnection.transportControls.sendCustomAction(TracklistContract.ACTION_CYCLE_REPEAT, null)
        }

        binding.scrubSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.elapsedTimeText.text = formatDurationMs(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                lastSeekRequestTimeMs = SystemClock.elapsedRealtime()
                val targetMs = (seekBar?.progress ?: 0).toLong()
                musicServiceConnection.transportControls.seekTo(targetMs)
            }
        })
    }

    private fun updateDurationAndPosition() {
        val currentMode = musicServiceConnection.sessionExtras.value?.getString(TracklistContract.EXTRA_PLAYBACK_MODE)
            ?: currentPlaybackState.extras?.getString(TracklistContract.EXTRA_PLAYBACK_MODE)
        val durationMs = musicServiceConnection.durationMs()
        val isRadio = currentMode == PlaybackMode.RADIO.name || (currentPlaybackState.isPlaying && durationMs <= 0L && currentMode != PlaybackMode.TRACKLIST.name)

        if (isRadio) {
            binding.scrubSeekBar.isEnabled = false
            binding.scrubSeekBar.max = 0
            binding.scrubSeekBar.progress = 0
            binding.totalTimeText.text = "LIVE"
            binding.elapsedTimeText.text = ""
            return
        }

        if (durationMs > 0L) {
            binding.scrubSeekBar.isEnabled = true
            if (binding.scrubSeekBar.max != durationMs.toInt()) {
                binding.scrubSeekBar.max = durationMs.toInt()
            }
            binding.totalTimeText.text = formatDurationMs(durationMs)
        } else {
            binding.scrubSeekBar.isEnabled = false
            binding.scrubSeekBar.max = 0
            binding.totalTimeText.text = "-:--"
        }
        val isSeekingOrSettling = isUserSeeking || (SystemClock.elapsedRealtime() - lastSeekRequestTimeMs < 800L)
        if (!isSeekingOrSettling) {
            val currentPos = musicServiceConnection.currentPositionMs()
            val clampedPos = if (durationMs > 0L) currentPos.coerceIn(0L, durationMs) else currentPos.coerceAtLeast(0L)
            binding.scrubSeekBar.progress = clampedPos.toInt()
            binding.elapsedTimeText.text = formatDurationMs(clampedPos)
        }
    }

    private fun updateNowPlaying(metadata: MediaMetadata) {
        val mediaId = metadata.mediaId
        if (!mediaId.isNullOrBlank()) {
            val title = metadata.title ?: getString(R.string.nothing_playing)
            val sub = metadata.subtitle?.toString() ?: ""
            val artBytes = metadata.artworkData
            val artHash = artBytes?.contentHashCode() ?: 0

            val metadataUnchanged = mediaId == lastBoundMediaId &&
                title == lastBoundTitle &&
                sub == lastBoundSubtitle &&
                artHash == lastBoundArtHash

            if (!metadataUnchanged) {
                lastBoundMediaId = mediaId
                lastBoundTitle = title
                lastBoundSubtitle = sub
                lastBoundArtHash = artHash

                binding.playerTrackTitle.text = title
                binding.playerTrackSubtitle.text = sub
                binding.playerTrackSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

                val bmp = metadata.iconBitmap
                if (bmp != null) {
                    binding.playerAlbumArt.setImageBitmap(bmp)
                } else {
                    binding.playerAlbumArt.setImageResource(R.drawable.music_note_24px)
                }
            }

            updateDurationAndPosition()
        } else {
            if (lastBoundMediaId != null) {
                lastBoundMediaId = null
                lastBoundTitle = null
                lastBoundSubtitle = null
                lastBoundArtHash = null

                binding.playerTrackTitle.text = "Select a song to play"
                binding.playerTrackSubtitle.text = ""
                binding.playerTrackSubtitle.visibility = View.GONE
                binding.playerAlbumArt.setImageResource(R.drawable.music_note_24px)
                binding.scrubSeekBar.isEnabled = false
                binding.scrubSeekBar.progress = 0
                binding.scrubSeekBar.max = 0
                binding.elapsedTimeText.text = ""
                binding.totalTimeText.text = "-:--"
            }
        }
    }

    private fun updatePlaybackState(state: GTRadioPlaybackState) {
        if (state.isPlaying) {
            binding.playPauseButton.setImageResource(R.drawable.pause_24px)
            startTicker()
        } else {
            binding.playPauseButton.setImageResource(R.drawable.play_arrow_24px)
            stopTicker()
        }

        val shuffleOn = state.extras?.getBoolean(TracklistContract.EXTRA_SHUFFLE_ENABLED, false) ?: false
        if (shuffleOn) {
            binding.shuffleButton.setColorFilter(ContextCompat.getColor(this, R.color.yellow_500))
            binding.shuffleButton.alpha = 1.0f
        } else {
            binding.shuffleButton.setColorFilter(Color.GRAY)
            binding.shuffleButton.alpha = 0.5f
        }

        val repeatMode = state.extras?.getInt(TracklistContract.EXTRA_REPEAT_MODE, TracklistContract.REPEAT_MODE_NONE)
            ?: TracklistContract.REPEAT_MODE_NONE
        when (repeatMode) {
            TracklistContract.REPEAT_MODE_ONE -> {
                binding.repeatButton.setImageResource(R.drawable.repeat_one_24px)
                binding.repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.yellow_500))
                binding.repeatButton.alpha = 1.0f
            }
            TracklistContract.REPEAT_MODE_ALL -> {
                binding.repeatButton.setImageResource(R.drawable.repeat_24px)
                binding.repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.yellow_500))
                binding.repeatButton.alpha = 1.0f
            }
            else -> {
                binding.repeatButton.setImageResource(R.drawable.repeat_24px)
                binding.repeatButton.setColorFilter(Color.GRAY)
                binding.repeatButton.alpha = 0.5f
            }
        }

        updateDurationAndPosition()
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.ROOT, "%d:%02d", min, sec)
    }

    private inner class TracklistAdapter : RecyclerView.Adapter<TracklistViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TracklistViewHolder {
            val itemBinding = ItemTracklistRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TracklistViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: TracklistViewHolder, position: Int) {
            val item = currentItems[position]
            val meta = item.mediaMetadata

            holder.binding.rowTitle.text = meta.title
            holder.binding.rowSubtitle.text = meta.subtitle ?: ""

            when (currentLevel) {
                NavLevel.SERIES -> {
                    holder.binding.rowIcon.setImageResource(R.drawable.outline_radio_24)
                    holder.binding.rowPlayingIndicator.visibility = View.GONE
                    holder.binding.cardView.strokeWidth = 0
                }
                NavLevel.STATIONS -> {
                    holder.binding.rowIcon.setImageResource(R.drawable.outline_radio_24)
                    holder.binding.rowPlayingIndicator.visibility = View.GONE
                    holder.binding.cardView.strokeWidth = 0
                }
                NavLevel.SONGS -> {
                    val curMediaId = currentNowPlaying.mediaId ?: ""
                    val isPlayingThis = curMediaId.isNotBlank() && curMediaId == item.mediaId
                    if (isPlayingThis) {
                        holder.binding.rowPlayingIndicator.visibility = View.VISIBLE
                        holder.binding.cardView.strokeWidth = 2.dpToPx()
                        holder.binding.cardView.strokeColor = ContextCompat.getColor(this@TracklistActivity, R.color.yellow_500)
                    } else {
                        holder.binding.rowPlayingIndicator.visibility = View.GONE
                        holder.binding.cardView.strokeWidth = 0
                    }

                    val artist = meta.extras?.getString(TracklistContract.EXTRA_TRACK_ARTIST)
                        ?: meta.subtitle?.toString() ?: ""
                    val title = meta.extras?.getString(TracklistContract.EXTRA_TRACK_TITLE)
                        ?: meta.title?.toString() ?: ""
                    val key = "$artist - $title"
                    holder.binding.rowIcon.tag = key

                    val memArt = AlbumArtDownloader.getMemoryCachedAlbumArt(artist, title)
                    if (memArt != null) {
                        holder.binding.rowIcon.setImageBitmap(memArt)
                    } else {
                        holder.binding.rowIcon.setImageResource(R.drawable.music_note_24px)
                        lifecycleScope.launch(Dispatchers.IO) {
                            val diskArt = AlbumArtDownloader.getCachedAlbumArt(this@TracklistActivity, artist, title)
                            if (diskArt != null) {
                                withContext(Dispatchers.Main.immediate) {
                                    if (holder.binding.rowIcon.tag == key) {
                                        holder.binding.rowIcon.setImageBitmap(diskArt)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            holder.itemView.setOnClickListener {
                when (currentLevel) {
                    NavLevel.SERIES -> {
                        val groupId = item.mediaId.removePrefix(TracklistContract.GROUP_PREFIX)
                        val name = meta.title?.toString()
                        navigateTo(NavLevel.STATIONS, groupId, name)
                    }
                    NavLevel.STATIONS -> {
                        val stationId = item.mediaId.removePrefix(TracklistContract.STATION_PREFIX)
                        val name = meta.title?.toString()
                        navigateTo(NavLevel.SONGS, stationId, name)
                    }
                    NavLevel.SONGS -> {
                        musicServiceConnection.transportControls.playFromMediaId(item.mediaId, null)
                    }
                }
            }
        }

        override fun getItemCount(): Int = currentItems.size
    }

    private class TracklistViewHolder(val binding: ItemTracklistRowBinding) :
        RecyclerView.ViewHolder(binding.root)
}
