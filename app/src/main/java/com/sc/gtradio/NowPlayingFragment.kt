package com.sc.gtradio

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.sc.gtradio.databinding.FragmentNowplayingBinding
import com.sc.gtradio.media.*
import com.sc.gtradio.media.stations.TracklistContract
import com.sc.gtradio.utils.InjectorUtils

class NowPlayingFragment : Fragment() {
    private var _binding: FragmentNowplayingBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null
    private var playbackState: PlaybackStateCompat = EMPTY_PLAYBACK_STATE

    private val playbackStateObserver = Observer<PlaybackStateCompat> {
        playbackState = it ?: EMPTY_PLAYBACK_STATE
        val metadata = musicServiceConnection?.nowPlaying?.value ?: NOTHING_PLAYING
        updateState(playbackState, metadata)
    }

    private val mediaMetadataObserver = Observer<MediaMetadataCompat> {
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
            val isTracklist = musicServiceConnection?.nowPlaying?.value?.description?.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateState(playbackState: PlaybackStateCompat, mediaMetadata: MediaMetadataCompat) {
        if (_binding == null) return

        if (mediaMetadata.description.mediaId?.isNotBlank() == true) {
            binding.stationTitle.text = mediaMetadata.description.title
            val sub = mediaMetadata.description.subtitle?.toString() ?: ""
            binding.stationAlbum.text = sub
            binding.stationAlbum.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            val bmp = mediaMetadata.description.iconBitmap
            val isTracklist = mediaMetadata.description.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
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
            val isTracklist = mediaMetadata.description.mediaId?.startsWith(TracklistContract.SONG_PREFIX) == true
            binding.playStopButton.setImageResource(if (isTracklist) R.drawable.pause_24px else R.drawable.stop_24px)
        } else {
            binding.playStopButton.setImageResource(R.drawable.play_arrow_24px)
        }

        binding.previousButton.visibility = if (playbackState.isSkipToPreviousEnabled) View.VISIBLE else View.INVISIBLE
        binding.nextButton.visibility = if (playbackState.isSkipToNextEnabled) View.VISIBLE else View.INVISIBLE
    }
}
