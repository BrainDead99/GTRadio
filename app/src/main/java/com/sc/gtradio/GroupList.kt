package com.sc.gtradio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sc.gtradio.common.DynamicThemeManager
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.databinding.FragmentGrouplistBinding
import com.sc.gtradio.databinding.FragmentStationgroupitemBinding
import com.sc.gtradio.media.MusicServiceConnection
import com.sc.gtradio.media.SubscriptionCallback
import com.sc.gtradio.utils.InjectorUtils

class GroupList : Fragment() {
    private var _binding: FragmentGrouplistBinding? = null
    private var musicServiceConnection: MusicServiceConnection? = null

    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            GTRadioLogger.log("GroupList.onChildrenLoaded: parentId=$parentId, count=${children.size}")
            if (_binding != null) {
                binding.groupLoadingProgress.visibility = View.GONE
                val hasValidFolder = hasValidLibraryPermission()
                if (children.isEmpty()) {
                    binding.stationList.visibility = View.GONE
                    if (hasValidFolder) {
                        binding.textviewNogroups.visibility = View.VISIBLE
                        binding.sadFaceIcon.visibility = View.VISIBLE
                        binding.buttonSelect.visibility = View.GONE
                        binding.textviewNofolder.visibility = View.GONE
                        binding.textviewDisclaimer.visibility = View.GONE
                    } else {
                        binding.textviewNogroups.visibility = View.INVISIBLE
                        binding.sadFaceIcon.visibility = View.INVISIBLE
                        binding.buttonSelect.visibility = View.VISIBLE
                        binding.textviewNofolder.visibility = View.VISIBLE
                        binding.textviewDisclaimer.visibility = View.VISIBLE
                    }
                } else {
                    binding.stationList.visibility = View.VISIBLE
                    binding.buttonSelect.visibility = View.GONE
                    binding.textviewNofolder.visibility = View.GONE
                    binding.textviewDisclaimer.visibility = View.GONE
                    binding.textviewNogroups.visibility = View.INVISIBLE
                    binding.sadFaceIcon.visibility = View.INVISIBLE
                }
            }
            listAdapter.submitList(children)
        }
    }

    private val listAdapter = StationGroupMediaItemListAdapter { clickedItem ->
        val groupName = clickedItem.mediaMetadata.title?.toString() ?: ""
        musicServiceConnection?.sendCommand(
            "setActiveStationGroupId",
            Bundle().apply {
                putString("stationGroupId", clickedItem.mediaId)
                putString("groupName", groupName)
            }
        )
        (activity as MainActivity).navigateToRadioList(clickedItem.mediaId, groupName)
    }

    private val binding get() = _binding!!

    private val contract = object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            val intent = super.createIntent(context, input)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            return intent
        }
    }

    private val permissionResultLauncher = registerForActivityResult(contract) { result: Uri? ->
        if (result == null) {
            GTRadioLogger.log("GroupList: Folder picker cancelled by user.")
            return@registerForActivityResult
        }
        GTRadioLogger.log("GroupList: Folder selected: $result")
        try {
            activity?.contentResolver?.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            GTRadioLogger.log("GroupList: takePersistableUriPermission error", e)
        }
        val sharedPref = activity?.applicationContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        with(sharedPref?.edit()) {
            this?.putString(getString(R.string.radio_folders_uri_key), result.toString())
            this?.apply()
        }
        updateLibrarySelectedComponents()
        reloadRootSubscription()
    }

    private val sharedPrefListener = GroupListOnSharedPrefChange()
    private inner class GroupListOnSharedPrefChange : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (prefs != null && _binding != null && isAdded && key == getString(R.string.radio_folders_uri_key)) {
                updateLibrarySelectedComponents()
                reloadRootSubscription()
            }
        }
    }

    private fun reloadRootSubscription() {
        if (_binding != null && hasValidLibraryPermission() && listAdapter.currentList.isEmpty()) {
            binding.groupLoadingProgress.visibility = View.VISIBLE
        }
        musicServiceConnection?.unsubscribe("root", subscriptionCallback)
        musicServiceConnection?.subscribe("root", subscriptionCallback)
    }

    private fun hasValidLibraryPermission(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx)
        val radioUri = sharedPref.getString(getString(R.string.radio_folders_uri_key), "")
        if (radioUri.isNullOrEmpty()) return false
        return activity?.contentResolver?.persistedUriPermissions?.any { x ->
            x.uri == Uri.parse(radioUri) && x.isReadPermission
        } == true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        GTRadioLogger.init(requireContext())
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(requireActivity().applicationContext)
        sharedPref.registerOnSharedPreferenceChangeListener(sharedPrefListener)

        if (musicServiceConnection == null) {
            musicServiceConnection = InjectorUtils.provideMusicServiceConnection(requireContext()).also {
                it.subscribe("root", subscriptionCallback)
            }
        }

        _binding = FragmentGrouplistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonSelect.setOnClickListener {
            GTRadioLogger.log("GroupList: Select button tapped, launching folder picker.")
            permissionResultLauncher.launch(null)
        }
        binding.stationList.layoutManager = LinearLayoutManager(context)
        binding.stationList.adapter = listAdapter
        updateLibrarySelectedComponents()
    }

    override fun onResume() {
        super.onResume()
        updateLibrarySelectedComponents()
        reloadRootSubscription()
    }

    private fun updateLibrarySelectedComponents() {
        if (_binding == null) return
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(requireActivity().applicationContext)
        val radioUri = sharedPref?.getString(getString(R.string.radio_folders_uri_key), "")
        if (!hasValidLibraryPermission()) {
            if (!radioUri.isNullOrEmpty()) {
                with(sharedPref?.edit()) {
                    this?.putString(getString(R.string.radio_folders_uri_key), "")
                    this?.apply()
                }
            }
            binding.groupLoadingProgress.visibility = View.GONE
            binding.stationList.visibility = View.GONE
            binding.buttonSelect.visibility = View.VISIBLE
            binding.textviewNofolder.visibility = View.VISIBLE
            binding.textviewDisclaimer.visibility = View.VISIBLE
            binding.textviewNogroups.visibility = View.INVISIBLE
            binding.sadFaceIcon.visibility = View.INVISIBLE
        } else {
            binding.buttonSelect.visibility = View.GONE
            binding.textviewNofolder.visibility = View.GONE
            binding.textviewDisclaimer.visibility = View.GONE
            if (listAdapter.currentList.isEmpty()) {
                binding.groupLoadingProgress.visibility = View.VISIBLE
                binding.stationList.visibility = View.GONE
                binding.textviewNogroups.visibility = View.INVISIBLE
                binding.sadFaceIcon.visibility = View.INVISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(requireActivity().applicationContext)
        sharedPref.unregisterOnSharedPreferenceChangeListener(sharedPrefListener)
        _binding = null
    }
}

class StationGroupMediaItemListAdapter(
    private val itemClickedListener: (MediaItem) -> Unit
) : ListAdapter<MediaItem, StationGroupViewHolder>(MediaItemDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationGroupViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentStationgroupitemBinding.inflate(inflater, parent, false)
        return StationGroupViewHolder(binding, itemClickedListener)
    }

    override fun onBindViewHolder(holder: StationGroupViewHolder, position: Int, payloads: MutableList<Any>) {
        val mediaItem = getItem(position)
        if (payloads.isEmpty()) {
            holder.item = mediaItem
            holder.titleView.text = mediaItem.mediaMetadata.title
            val artUri = mediaItem.mediaMetadata.artworkUri
            val bmp = if (artUri != null) DynamicThemeManager.loadBitmap(holder.itemView.context, artUri, 512) else null
            if (bmp != null) {
                holder.groupArt.setImageBitmap(bmp)
            } else {
                holder.groupArt.setImageResource(R.drawable.outline_radio_24)
            }
        }
    }

    override fun onBindViewHolder(holder: StationGroupViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }
}

class StationGroupViewHolder(
    binding: FragmentStationgroupitemBinding,
    itemClickedListener: (MediaItem) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    val titleView: TextView = binding.groupTitle
    val groupArt: ImageView = binding.groupArt
    var item: MediaItem? = null

    init {
        binding.root.setOnClickListener { item?.let { itemClickedListener(it) } }
    }
}
