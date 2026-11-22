package com.sc.gtradio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.media3.common.MediaMetadata
import com.sc.gtradio.databinding.FragmentNowplayingBinding
import com.sc.gtradio.media.*
import com.sc.gtradio.media.stations.TracklistContract
import com.sc.gtradio.utils.InjectorUtils

class NowPlayingFragment : Fragment() {
    private var _binding: FragmentNowplayingBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null
    private var playbackState: GTRadioPlaybackState = EMPTY_PLAYBACK_STATE

    private val playbackStateObserver = Observer<GTRadioPlaybackState> {
        playbackState = it ?: EMPTY_PLAYBACK_STATE
        val metadata = musicServiceConnection?.nowPlaying?.value ?: NOTHING_PLAYING
        updateState(playbackState, metadata)
    }

    private val mediaMetadataObserver = Observer<MediaMetadata> {
        updateState(playbackState, it)
    }

    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (musicServiceConnection == null) {
            musicServiceConnection = InjectorUtils.provideMusicServiceConnection(requireContext()).also {
                it.playbackState.observe(viewLifecycleOwner, playbackStateObserver)
                it.nowPlaying.observe(viewLifecycleOwner, mediaMetadataObserver)
            }
        }
        _binding = FragmentNowplayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stationTitle.isSelected = true

        binding.playStopButton.setOnClickListener {
            val isTracklist = musicServiceConnection?.nowPlaying?.value?.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
            if (playbackState.isPlaying) {
                if (isTracklist) {
                    musicServiceConnection?.transportControls?.pause()
                } else {
                    musicServiceConnection?.transportControls?.stop()
                }
            } else {
                musicServiceConnection?.transportControls?.play()
            }
        }
        binding.nextButton.setOnClickListener {
            musicServiceConnection?.transportControls?.skipToNext()
        }
        binding.previousButton.setOnClickListener {
            musicServiceConnection?.transportControls?.skipToPrevious()
        }

        binding.stationTitle.text = getString(R.string.nothing_playing)
        binding.stationAlbum.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        val conn = musicServiceConnection
        if (conn != null && _binding != null) {
            val state = conn.playbackState.value ?: EMPTY_PLAYBACK_STATE
            val meta = conn.nowPlaying.value ?: NOTHING_PLAYING
            playbackState = state
            updateState(state, meta)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateState(playbackState: GTRadioPlaybackState, mediaMetadata: MediaMetadata) {
        if (_binding == null) return

        if (!mediaMetadata.mediaId.isNullOrBlank()) {
            binding.stationTitle.text = mediaMetadata.title
            val sub = mediaMetadata.subtitle?.toString() ?: ""
            binding.stationAlbum.text = sub
            binding.stationAlbum.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            val bmp = mediaMetadata.iconBitmap
            val isTracklist = mediaMetadata.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
            if (bmp != null) {
                binding.albumArt.setImageBitmap(bmp)
            } else {
                binding.albumArt.setImageResource(if (isTracklist) R.drawable.music_note_24px else R.drawable.outline_radio_24)
            }
        } else {
            binding.stationTitle.text = getString(R.string.nothing_playing)
            binding.stationAlbum.text = ""
            binding.stationAlbum.visibility = View.GONE
            binding.albumArt.setImageResource(R.drawable.outline_radio_24)
        }

        if (playbackState.isPlaying) {
            val isTracklist = mediaMetadata.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
            binding.playStopButton.setImageResource(if (isTracklist) R.drawable.pause_24px else R.drawable.stop_24px)
        } else {
            binding.playStopButton.setImageResource(R.drawable.play_arrow_24px)
        }

        binding.previousButton.visibility = if (playbackState.isSkipToPreviousEnabled) View.VISIBLE else View.INVISIBLE
        binding.nextButton.visibility = if (playbackState.isSkipToNextEnabled) View.VISIBLE else View.INVISIBLE
    }
}
