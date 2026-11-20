package com.sc.gtradio

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sc.gtradio.databinding.ActivityTracklistBinding
import com.sc.gtradio.databinding.ItemTracklistRowBinding
import com.sc.gtradio.media.EMPTY_PLAYBACK_STATE
import com.sc.gtradio.media.MusicServiceConnection
import com.sc.gtradio.media.NOTHING_PLAYING
import com.sc.gtradio.media.isPlaying
import com.sc.gtradio.media.stations.AlbumArtDownloader
import com.sc.gtradio.media.stations.TracklistContract
import com.sc.gtradio.utils.InjectorUtils
import java.util.Locale
import java.util.concurrent.Executors

class TracklistActivity : AppCompatActivity() {

    enum class NavLevel {
        SERIES,
        STATIONS,
        SONGS
    }

    private lateinit var binding: ActivityTracklistBinding
    private lateinit var musicServiceConnection: MusicServiceConnection
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()

    private var currentLevel = NavLevel.SERIES
    private var currentGroupId: String? = null
    private var currentGroupName: String? = null
    private var currentStationId: String? = null
    private var currentStationName: String? = null
    private var currentSubscribedId: String? = null

    private val currentItems = mutableListOf<MediaBrowserCompat.MediaItem>()
    private lateinit var adapter: TracklistAdapter

    private var currentPlaybackState: PlaybackStateCompat = EMPTY_PLAYBACK_STATE
    private var currentNowPlaying: MediaMetadataCompat = NOTHING_PLAYING
    private var isUserSeeking = false
    private var isActivityResumed = false

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isActivityResumed && currentPlaybackState.isPlaying && !isUserSeeking) {
                val currentPos = currentPlaybackState.position +
                    ((SystemClock.elapsedRealtime() - currentPlaybackState.lastPositionUpdateTime) * currentPlaybackState.playbackSpeed).toLong()
                val clampedPos = currentPos.coerceAtLeast(0L)
                binding.scrubSeekBar.progress = clampedPos.toInt()
                binding.elapsedTimeText.text = formatDurationMs(clampedPos)
            }
            if (isActivityResumed && currentPlaybackState.isPlaying) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
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

    private val connectionObserver = Observer<Boolean> { connected ->
        if (connected == true && currentSubscribedId == null) {
            navigateTo(NavLevel.SERIES)
        }
    }

    private val playbackStateObserver = Observer<PlaybackStateCompat> { state ->
        currentPlaybackState = state ?: EMPTY_PLAYBACK_STATE
        updatePlaybackState(currentPlaybackState)
        adapter.notifyDataSetChanged()
    }

    private val nowPlayingObserver = Observer<MediaMetadataCompat> { metadata ->
        currentNowPlaying = metadata ?: NOTHING_PLAYING
        updateNowPlaying(currentNowPlaying)
        adapter.notifyDataSetChanged()
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
        currentSubscribedId?.let { musicServiceConnection.unsubscribe(it, subscriptionCallback) }
        artExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun startTicker() {
        stopTicker()
        if (currentPlaybackState.isPlaying) {
            mainHandler.post(tickerRunnable)
        }
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(tickerRunnable)
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
                val curMediaId = currentNowPlaying.description.mediaId
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
                val targetMs = (seekBar?.progress ?: 0).toLong()
                musicServiceConnection.transportControls.seekTo(targetMs)
            }
        })
    }

    private fun updateNowPlaying(metadata: MediaMetadataCompat) {
        val mediaId = metadata.description.mediaId
        if (!mediaId.isNullOrBlank()) {
            binding.playerTrackTitle.text = metadata.description.title ?: getString(R.string.nothing_playing)
            val sub = metadata.description.subtitle?.toString() ?: ""
            binding.playerTrackSubtitle.text = sub
            binding.playerTrackSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            val bmp = metadata.description.iconBitmap
            if (bmp != null) {
                binding.playerAlbumArt.setImageBitmap(bmp)
            } else {
                binding.playerAlbumArt.setImageResource(R.drawable.music_note_24px)
            }

            val durationMs = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            if (durationMs > 0) {
                binding.scrubSeekBar.max = durationMs.toInt()
                binding.totalTimeText.text = formatDurationMs(durationMs)
            } else {
                binding.scrubSeekBar.max = 0
                binding.totalTimeText.text = "-:--"
            }
        } else {
            binding.playerTrackTitle.text = "Select a song to play"
            binding.playerTrackSubtitle.text = ""
            binding.playerTrackSubtitle.visibility = View.GONE
            binding.playerAlbumArt.setImageResource(R.drawable.music_note_24px)
            binding.scrubSeekBar.progress = 0
            binding.scrubSeekBar.max = 0
            binding.elapsedTimeText.text = "0:00"
            binding.totalTimeText.text = "-:--"
        }
    }

    private fun updatePlaybackState(state: PlaybackStateCompat) {
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

        val repeatMode = state.extras?.getInt(TracklistContract.EXTRA_REPEAT_MODE, PlaybackStateCompat.REPEAT_MODE_NONE)
            ?: PlaybackStateCompat.REPEAT_MODE_NONE
        when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> {
                binding.repeatButton.setImageResource(R.drawable.repeat_one_24px)
                binding.repeatButton.setColorFilter(ContextCompat.getColor(this, R.color.yellow_500))
                binding.repeatButton.alpha = 1.0f
            }
            PlaybackStateCompat.REPEAT_MODE_ALL -> {
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

        if (!isUserSeeking && state.position >= 0) {
            binding.scrubSeekBar.progress = state.position.toInt()
            binding.elapsedTimeText.text = formatDurationMs(state.position)
        }
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
            val desc = item.description

            holder.binding.rowTitle.text = desc.title
            holder.binding.rowSubtitle.text = desc.subtitle ?: ""

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
                    val curMediaId = currentNowPlaying.description.mediaId ?: ""
                    val isPlayingThis = curMediaId.isNotBlank() && curMediaId == item.mediaId
                    if (isPlayingThis) {
                        holder.binding.rowPlayingIndicator.visibility = View.VISIBLE
                        holder.binding.cardView.strokeWidth = 2.dpToPx()
                        holder.binding.cardView.strokeColor = ContextCompat.getColor(this@TracklistActivity, R.color.yellow_500)
                    } else {
                        holder.binding.rowPlayingIndicator.visibility = View.GONE
                        holder.binding.cardView.strokeWidth = 0
                    }

                    val artist = desc.extras?.getString(TracklistContract.EXTRA_TRACK_ARTIST)
                        ?: desc.subtitle?.toString() ?: ""
                    val title = desc.extras?.getString(TracklistContract.EXTRA_TRACK_TITLE)
                        ?: desc.title?.toString() ?: ""
                    val key = "$artist - $title"
                    holder.binding.rowIcon.tag = key

                    val memArt = AlbumArtDownloader.getCachedAlbumArt(this@TracklistActivity, artist, title)
                    if (memArt != null) {
                        holder.binding.rowIcon.setImageBitmap(memArt)
                    } else {
                        holder.binding.rowIcon.setImageResource(R.drawable.music_note_24px)
                        artExecutor.submit {
                            val diskArt = AlbumArtDownloader.getCachedAlbumArt(this@TracklistActivity, artist, title)
                            if (diskArt != null) {
                                mainHandler.post {
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
                        val groupId = item.mediaId?.removePrefix(TracklistContract.GROUP_PREFIX)
                        val name = desc.title?.toString()
                        navigateTo(NavLevel.STATIONS, groupId, name)
                    }
                    NavLevel.STATIONS -> {
                        val stationId = item.mediaId?.removePrefix(TracklistContract.STATION_PREFIX)
                        val name = desc.title?.toString()
                        navigateTo(NavLevel.SONGS, stationId, name)
                    }
                    NavLevel.SONGS -> {
                        item.mediaId?.let { mediaId ->
                            musicServiceConnection.transportControls.playFromMediaId(mediaId, null)
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = currentItems.size
    }

    private class TracklistViewHolder(val binding: ItemTracklistRowBinding) :
        RecyclerView.ViewHolder(binding.root)
}
