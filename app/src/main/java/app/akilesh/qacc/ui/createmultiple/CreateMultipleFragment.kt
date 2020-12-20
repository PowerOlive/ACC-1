package app.akilesh.qacc.ui.createmultiple

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.forEach
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StableIdKeyProvider
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import app.akilesh.qacc.Const.Colors.AEX
import app.akilesh.qacc.Const.Colors.brandColors
import app.akilesh.qacc.Const.prefix
import app.akilesh.qacc.Const.selected
import app.akilesh.qacc.R
import app.akilesh.qacc.databinding.CreateAllFragmentBinding
import app.akilesh.qacc.model.Accent
import app.akilesh.qacc.model.Colour
import app.akilesh.qacc.ui.home.accent.AccentViewModel
import app.akilesh.qacc.utils.AppUtils.getColorAccent
import app.akilesh.qacc.utils.AppUtils.getThemeColor
import app.akilesh.qacc.utils.AppUtils.getWallpaperColors
import com.afollestad.assent.Permission
import com.afollestad.assent.coroutines.awaitPermissionsResult
import com.afollestad.assent.rationale.createDialogRationale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CreateAllFragment: Fragment() {

    private lateinit var binding: CreateAllFragmentBinding
    private val viewModel: CreateMultipleViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = CreateAllFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.accentListRv) {v, insets ->
            v.updatePadding(
                top = insets.getInsets(systemBars()).top,
                bottom = insets.getInsets(systemBars()).bottom
            )
            insets
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useSystemAccent = sharedPreferences.getBoolean("system_accent", false)
        val iconTint = if (useSystemAccent) ColorStateList.valueOf(requireContext().getColorAccent())
        else ColorStateList.valueOf(requireContext().getThemeColor(R.attr.colorPrimary))
        binding.createMultipleBottomAppBar.menu.forEach {
            it.iconTintList = iconTint
        }

        val createAllAdapter = CreateMultipleAdapter()

        val allColors = (AEX + brandColors) as MutableList<Colour>
        if (SDK_INT > O) {
            val rationaleHandler = createDialogRationale(R.string.app_name) {
                onPermission(
                    Permission.READ_EXTERNAL_STORAGE,
                    getString(R.string.read_storage_permission_rationale)
                )
            }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                val result = awaitPermissionsResult(
                    Permission.READ_EXTERNAL_STORAGE,
                    rationaleHandler = rationaleHandler
                )
                if (result.isAllGranted()) {
                    allColors.addAll(requireContext().getWallpaperColors())
                    createAllAdapter.submitList(allColors)
                }
            }
        }

        binding.accentListRv.apply {
            adapter = createAllAdapter
            layoutManager = GridLayoutManager(
                requireContext(),
                2,
                GridLayoutManager.VERTICAL,
                false
            )
            edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
                override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                    return EdgeEffect(view.context).apply {
                        color = requireContext().getColorAccent()
                    }
                }
            }
        }

        val selectionTracker = SelectionTracker.Builder(
            "selection",
            binding.accentListRv,
            StableIdKeyProvider(binding.accentListRv),
            CreateMultipleItemDetailsLookup(
                binding.accentListRv
            ),
            StorageStrategy.createLongStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        CreateMultipleAdapter.initTracker(selectionTracker)

        setupBottomAppBarIcons(selectionTracker.hasSelection())
        binding.createMultipleBottomAppBar.setOnMenuItemClickListener {
            val temp = arrayListOf<Long>()
            createAllAdapter.currentList.forEachIndexed { index, _ ->
                temp.add(index.toLong())
            }
            when(it.itemId) {
                R.id.select -> {
                    selectionTracker.setItemsSelected(temp, true)
                }
                R.id.deselect -> {
                    selectionTracker.clearSelection()
                }
                R.id.invert -> {
                    temp.forEach { key ->
                        if (selectionTracker.isSelected(key)) selectionTracker.deselect(key)
                        else selectionTracker.select(key)
                    }
                }
            }
            true
        }

        selectionTracker.addObserver(
            object : SelectionTracker.SelectionObserver<Long>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    setupBottomAppBarIcons(selectionTracker.hasSelection())
                    val selection = selectionTracker.selection
                    selected = selection.mapNotNull { createAllAdapter.currentList[it.toInt()] }.toSet()
                }
            }
        )

        binding.createMultipleFab.setOnClickListener {
            if (selected.isNotEmpty()) {
                Log.d("selection", selected.toString())
                viewModel.createAll()
                viewModel.createAllWorkerId?.let { uuid ->
                    viewModel.workManager.getWorkInfoByIdLiveData(uuid)
                        .observe(viewLifecycleOwner, { workInfo ->
                            Log.d("id", workInfo.id.toString())
                            Log.d("state", workInfo.state.name)

                            when (workInfo.state) {
                                WorkInfo.State.RUNNING -> {
                                    binding.createMultipleFab.hide()
                                    binding.createMultipleBottomAppBar.performHide()
                                }

                                WorkInfo.State.SUCCEEDED -> {
                                    val accentViewModel by viewModels<AccentViewModel>()
                                    selected.forEach { colour ->
                                        val pkgName = prefix + "hex_" + colour.hex.removePrefix("#")
                                        val accent =
                                            Accent(pkgName, colour.name, colour.hex, colour.hex)
                                        accentViewModel.insert(accent)
                                    }
                                    Toast.makeText(
                                        requireContext(),
                                        String.format(getString(R.string.accents_created)),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    findNavController().navigate(R.id.action_global_home)
                                }
                                WorkInfo.State.FAILED -> {
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.error),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                else -> {
                                }
                            }
                        })
                }
            }
        }
    }

    private fun setupBottomAppBarIcons(flag: Boolean) {
        binding.createMultipleBottomAppBar.menu.apply {
            getItem(0).isVisible = flag
            getItem(1).isVisible = flag
        }
        binding.createMultipleFab.apply {
            binding.createMultipleFab.isEnabled = flag
            imageTintList = if (binding.createMultipleFab.isEnabled) null
            else ColorStateList.valueOf(Color.GRAY)
        }
    }
}
