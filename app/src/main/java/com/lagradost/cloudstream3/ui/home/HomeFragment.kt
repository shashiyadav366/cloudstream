package com.lagradost.cloudstream3.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentHomeBinding
import com.lagradost.cloudstream3.databinding.HomeEpisodesExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.databinding.TvtypesChipsBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.EmptyEvent
import com.lagradost.cloudstream3.utils.SubtitleHelper.getFlagFromIso
import com.lagradost.cloudstream3.utils.TvChannelUtils
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes
import com.lagradost.cloudstream3.utils.UIHelper.toPx

private const val TAG = "HomeFragment"

class HomeFragment : BaseFragment<FragmentHomeBinding>(
    BindingCreator.Bind(FragmentHomeBinding::bind)
) {
    companion object {
        // Used for configuration changed events to fix any popups that are not attached to a fragment
        val configEvent = EmptyEvent()
        var currentSpan = 1

        private val errorProfilePics = listOf(
            R.drawable.monke_benene,
            R.drawable.monke_burrito,
            R.drawable.monke_coco,
            R.drawable.monke_cookie,
            R.drawable.monke_flusdered,
            R.drawable.monke_funny,
            R.drawable.monke_like,
            R.drawable.monke_party,
            R.drawable.monke_sob,
            R.drawable.monke_drink,
        )

        val errorProfilePic = errorProfilePics.random()

        fun Context.getDisplayName(apiName: String?): String? {
            return when (apiName) {
                noneApi.name -> getString(R.string.none)
                randomApi.name -> getString(R.string.home_random)
                else -> apiName
            }
        }

        // Returns the GitHub author name a provider plugin's repository was published
        // under, or null for built-in/non-plugin providers. Falls back to the repo name.
        fun Context.getRepositoryAuthorForProvider(api: MainAPI): String? {
            val source = api.sourcePlugin ?: return null
            val folderName = File(source).parentFile?.name ?: return null
            val repositories = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
                ?: emptyArray()) + RepositoryManager.PREBUILT_REPOSITORIES
            val repository = repositories.firstOrNull {
                PluginManager.getPluginSanitizedFileName(it.url) == folderName
            } ?: return null
            return RepositoryManager.getRepositoryAuthor(repository.url) ?: repository.name
        }

        // Builds the provider label "baseName - author" where the author suffix is
        // rendered in a very small font so identical provider names can be told apart.
        fun Context.getProviderSelectorLabel(api: MainAPI, isMultiLang: Boolean): CharSequence {
            val baseName = getDisplayName(api.name) ?: api.name
            val author = getRepositoryAuthorForProvider(api)
            val prefix =
                if (isMultiLang) "${getFlagFromIso(api.lang)?.plus(" ") ?: ""}" else ""
            if (author == null) return SpannableString("$prefix$baseName")
            val full = "$prefix$baseName - $author"
            return SpannableString(full).apply {
                setSpan(
                    RelativeSizeSpan(0.6f),
                    (prefix + baseName + " - ").length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        //fun Activity.loadHomepageList(
        //    item: HomePageList,
        //    deleteCallback: (() -> Unit)? = null,
        //) {
        //    loadHomepageList(
        //        expand = HomeViewModel.ExpandableHomepageList(item, 1, false),
        //        deleteCallback = deleteCallback,
        //        expandCallback = null
        //    )
        //}

        // returns a BottomSheetDialog that will be hidden with OwnHidden upon hide, and must be saved to be able call ownShow in onCreateView

        fun Activity.loadHomepageList(
            expand: HomeViewModel.ExpandableHomepageList,
            deleteCallback: (() -> Unit)? = null,
            expandCallback: (suspend (String) -> HomeViewModel.ExpandableHomepageList?)? = null,
            dismissCallback: (() -> Unit),
        ): BottomSheetDialog {
            val context = this
            val bottomSheetDialogBuilder = BottomSheetDialog(context)
            val binding: HomeEpisodesExpandedBinding = HomeEpisodesExpandedBinding.inflate(
                bottomSheetDialogBuilder.layoutInflater,
                null,
                false
            )
            bottomSheetDialogBuilder.setContentView(binding.root)
            //val title = bottomSheetDialogBuilder.findViewById<TextView>(R.id.home_expanded_text)!!

            //title.findViewTreeLifecycleOwner().lifecycle.addObserver()

            val item = expand.list
            binding.homeExpandedText.text = item.name
            // val recycle =
            //    bottomSheetDialogBuilder.findViewById<AutofitRecyclerView>(R.id.home_expanded_recycler)!!
            //val titleHolder =
            //    bottomSheetDialogBuilder.findViewById<FrameLayout>(R.id.home_expanded_drag_down)!!

            // main {
            //(bottomSheetDialogBuilder.ownerActivity as androidx.fragment.app.FragmentActivity?)?.supportFragmentManager?.fragments?.lastOrNull()?.viewLifecycleOwner?.apply {
            //    println("GOT LIFE: lifecycle $this")
            //    this.lifecycle.addObserver(object : DefaultLifecycleObserver {
            //        override fun onResume(owner: LifecycleOwner) {
            //            super.onResume(owner)
            //            println("onResume!!!!")
            //            bottomSheetDialogBuilder?.ownShow()
            //        }

            //        override fun onStop(owner: LifecycleOwner) {
            //            super.onStop(owner)
            //            bottomSheetDialogBuilder?.ownHide()
            //        }
            //    })
            //}
            // }
            //val delete = bottomSheetDialogBuilder.home_expanded_delete
            binding.homeExpandedDelete.isGone = deleteCallback == null
            if (deleteCallback != null) {
                binding.homeExpandedDelete.setOnClickListener {
                    try {
                        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        deleteCallback.invoke()
                                        bottomSheetDialogBuilder.dismissSafe(this)
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {}
                                }
                            }

                        builder.setTitle(R.string.clear_history)
                            .setMessage(
                                context.getString(R.string.delete_message).format(
                                    item.name
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }
            binding.homeExpandedDragDown.setOnClickListener {
                bottomSheetDialogBuilder.dismissSafe(this)
            }


            // Span settings
            binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
            binding.homeExpandedRecycler.setRecycledViewPool(SearchAdapter.sharedPool)
            binding.homeExpandedRecycler.adapter =
                SearchAdapter(binding.homeExpandedRecycler,item.isHorizontalImages) { callback ->
                    handleSearchClickCallback(callback)
                    if (callback.action == SEARCH_ACTION_LOAD || callback.action == SEARCH_ACTION_PLAY_FILE) {
                        bottomSheetDialogBuilder.ownHide() // we hide here because we want to resume it later
                        //bottomSheetDialogBuilder.dismissSafe(this)
                    }
                }.apply {
                    submitList(item.list)
                    hasNext = expand.hasNext
                }

            binding.homeExpandedRecycler.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = expand.list.name

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is SearchAdapter) return

                    val count = adapter.itemCount
                    val currentHasNext = adapter.hasNext
                    //!recyclerView.canScrollVertically(1)
                    if (!recyclerView.isRecyclerScrollable() && currentHasNext && expandCount != count) {
                        expandCount = count
                        ioSafe {
                            expandCallback?.invoke(name)?.let { newExpand ->
                                (recyclerView.adapter as? SearchAdapter?)?.apply {
                                    hasNext = newExpand.hasNext
                                    submitList(newExpand.list.list)
                                }
                            }
                        }
                    }
                }
            })

            val spanListener = Runnable {
                binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
                // We want to rebind everything to update the UI, however we also want to avoid
                // any animations ect, this is the easiest way to do this, and the most correct
                @SuppressLint("NotifyDataSetChanged")
                binding.homeExpandedRecycler.adapter?.notifyDataSetChanged()
            }

            configEvent += spanListener

            bottomSheetDialogBuilder.setOnDismissListener {
                dismissCallback.invoke()
                configEvent -= spanListener
            }

            //(recycle.adapter as SearchAdapter).notifyDataSetChanged()

            bottomSheetDialogBuilder.show()
            return bottomSheetDialogBuilder
        }

        private fun getPairList(
            anime: Chip?,
            cartoons: Chip?,
            tvs: Chip?,
            docs: Chip?,
            movies: Chip?,
            asian: Chip?,
            livestream: Chip?,
            torrent: Chip?,
            nsfw: Chip?,
            others: Chip?,
        ): List<Pair<Chip?, List<TvType>>> {
            // This list should be same order as home screen to aid navigation
            return listOf(
                Pair(movies, listOf(TvType.Movie)),
                Pair(tvs, listOf(TvType.TvSeries)),
                Pair(anime, listOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
                Pair(asian, listOf(TvType.AsianDrama)),
                Pair(cartoons, listOf(TvType.Cartoon)),
                Pair(docs, listOf(TvType.Documentary)),
                Pair(livestream, listOf(TvType.Live)),
                Pair(torrent, listOf(TvType.Torrent)),
                Pair(nsfw, listOf(TvType.NSFW)),
                Pair(others, listOf(TvType.Others)),
            )
        }

        private fun getPairList(header: TvtypesChipsBinding) = getPairList(
            header.homeSelectAnime,
            header.homeSelectCartoons,
            header.homeSelectTvSeries,
            header.homeSelectDocumentaries,
            header.homeSelectMovies,
            header.homeSelectAsian,
            header.homeSelectLivestreams,
            header.homeSelectTorrents,
            header.homeSelectNsfw,
            header.homeSelectOthers
        )

        fun validateChips(header: TvtypesChipsBinding?, validTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
            }
        }

        fun updateChips(header: TvtypesChipsBinding?, selectedTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                button?.isChecked =
                    button.isVisible && selectedTypes.any { types.contains(it) }
            }
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {
            bindChips(header, selectedTypes, validTypes, callback, null, null)
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int?,
            nextFocusUp: Int?
        ) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
                button?.isChecked = isValid && selectedTypes.any { types.contains(it) }
                button?.isFocusable = true
                if (isLayout(TV)) {
                    button?.isFocusableInTouchMode = true
                }

                if (nextFocusDown != null)
                    button?.nextFocusDownId = nextFocusDown

                if (nextFocusUp != null)
                    button?.nextFocusUpId = nextFocusUp

                button?.setOnCheckedChangeListener { _, isChecked ->
                    if (types.contains(TvType.NSFW)) {
                        if (isChecked) {
                            // If NSFW selected, uncheck all others
                            for ((sbutton, _) in pairList) {
                                if (sbutton != button) {
                                    sbutton?.setOnCheckedChangeListener(null)
                                    sbutton?.isChecked = false
                                    sbutton?.setOnCheckedChangeListener { v, c ->
                                        val p = pairList.find { it.first == v } ?: return@setOnCheckedChangeListener
                                        // re-trigger
                                        v.isChecked = c
                                    }
                                }
                            }
                            callback(listOf(TvType.NSFW))
                            return@setOnCheckedChangeListener
                        } else {
                            // If NSFW unchecked, select all others that are valid
                            val result = ArrayList<TvType>()
                            for ((sbutton, vvalidTypes) in pairList) {
                                if (sbutton == button) continue
                                val isValidBtn = validTypes.any { vvalidTypes.contains(it) }
                                if (isValidBtn) {
                                    sbutton?.setOnCheckedChangeListener(null)
                                    sbutton?.isChecked = true
                                    sbutton?.setOnCheckedChangeListener { v, c ->
                                        val p = pairList.find { it.first == v } ?: return@setOnCheckedChangeListener
                                        v.isChecked = c
                                    }
                                    result.addAll(vvalidTypes)
                                }
                            }
                            callback(result)
                            return@setOnCheckedChangeListener
                        }
                    } else {
                        // Another chip was checked -> if NSFW is currently checked, uncheck NSFW
                        val nsfwPair = pairList.find { it.second.contains(TvType.NSFW) }
                        val nsfwButton = nsfwPair?.first
                        if (nsfwButton?.isChecked == true) {
                            nsfwButton.setOnCheckedChangeListener(null)
                            nsfwButton.isChecked = false
                            nsfwButton.setOnCheckedChangeListener { v, c ->
                                val p = pairList.find { it.first == v } ?: return@setOnCheckedChangeListener
                                v.isChecked = c
                            }
                        }
                    }
                    val result = ArrayList<TvType>()
                    for ((sbutton, vvalidTypes) in pairList) {
                        if (sbutton?.isChecked == true)
                            result.addAll(vvalidTypes)
                    }
                    callback(result)
                }
            }
        }

        fun Context.selectHomepage(
            selectedApiName: String?,
            selectedApiSource: String? = null,
            callback: (MainAPI) -> Unit,
        ) {
            val validAPIs = filterProviderByPreferredMedia().toMutableList()

            validAPIs.add(0, randomApi)
            validAPIs.add(0, noneApi)
            //val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            //builder.setView(R.layout.home_select_mainpage)
            val builder =
                BottomSheetDialog(this)

            builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val binding: HomeSelectMainpageBinding = HomeSelectMainpageBinding.inflate(
                builder.layoutInflater,
                null,
                false
            )

            builder.setContentView(binding.root)
            builder.show()
            builder.let { dialog ->
                val isMultiLang = getApiProviderLangSettings().let { set ->
                    set.size > 1 || set.contains(AllLanguagesName)
                }
                //dialog.window?.setGravity(Gravity.BOTTOM)

                // On TV, focus starts on the list; the search field is focused by
                // navigating up from the list so the keyboard doesn't pop immediately.
                binding.listview1.post {
                    binding.listview1.requestFocus()
                }

                var currentApiName = selectedApiName
                var currentApiSource = selectedApiSource

                var currentValidApis: MutableList<MainAPI> = mutableListOf()
                val preSelectedTypes = DataStoreHelper.homePreference.toMutableList()

                // Current provider search query, empty shows everything
                var providerQuery = ""

                binding.cancelBtt.setOnClickListener {
                    dialog.dismissSafe()
                }

                binding.applyBtt.setOnClickListener {
                    if (currentApiName != selectedApiName) {
                        (
                            currentValidApis.firstOrNull {
                                it.name == currentApiName && it.sourcePlugin == currentApiSource
                            } ?: currentValidApis.firstOrNull { it.name == currentApiName }
                            )?.let(callback)
                    }
                    dialog.dismissSafe()
                }

                var pinnedphashset = DataStoreHelper.pinnedProviders.toHashSet()

                val listView = dialog.findViewById<ListView>(R.id.listview1)

                val arrayAdapter = object : ArrayAdapter<CharSequence>(
                    this, R.layout.sort_bottom_single_provider_choice,
                    mutableListOf()
                ) {
                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {
                        val view = convertView ?: LayoutInflater.from(context)
                            .inflate(R.layout.sort_bottom_single_provider_choice, parent, false)
                        val titleText = view.findViewById<TextView>(R.id.text1)
                        val pinIcon = view.findViewById<ImageView>(R.id.pinicon)
                        val settingsIcon = view.findViewById<ImageView>(R.id.action_settings)

                        val providerApi = currentValidApis[position]
                        titleText?.text = getProviderSelectorLabel(providerApi, isMultiLang)
                        val isPinned = DataStoreHelper.isProviderPinned(
                            pinnedphashset, providerApi.name, providerApi.sourcePlugin
                        )
                        pinIcon.visibility = if (isPinned) View.VISIBLE else View.GONE

                        val pluginInstance = providerApi.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                        val isDownloadedPluginWithSettings = pluginInstance?.openSettings != null && !isLayout(TV)

                        settingsIcon.visibility = if (isDownloadedPluginWithSettings) View.VISIBLE else View.GONE
                        if (isDownloadedPluginWithSettings) {
                            settingsIcon.setOnClickListener {
                                try {
                                    val activityContext = it.context.getActivity() ?: it.context
                                    pluginInstance.openSettings?.invoke(activityContext)
                                } catch (e: Throwable) {
                                    logError(e)
                                }
                            }
                        }

                        return view
                    }
                }
                listView?.adapter = arrayAdapter
                listView?.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                listView?.setOnItemClickListener { _, _, i, _ ->
                    if (currentValidApis.isNotEmpty()) {
                        currentApiName = currentValidApis[i].name
                        currentApiSource = currentValidApis[i].sourcePlugin
                        //to switch to apply simply remove this
                        currentValidApis[i].let(callback)
                        dialog.dismissSafe()
                    }
                }

                fun updateList() {
                    DataStoreHelper.homePreference = preSelectedTypes

                    // Upgrade legacy name-only pins (pre multi-pin support) to exact
                    // name+source keys before any pin state is used, so with identical
                    // plugin names only the actually pinned one shows the pin icon.
                    // Resolved against the full provider list, independent of filters.
                    val allApiMap = LinkedHashMap<String, MainAPI>().apply {
                        validAPIs.forEach {
                            put(DataStoreHelper.getProviderPinKey(it.name, it.sourcePlugin), it)
                        }
                    }
                    var pinnedp = DataStoreHelper.pinnedProviders.toList()
                    if (pinnedp.any { !allApiMap.containsKey(it) }) {
                        val claimedExactKeys = pinnedp.filterTo(HashSet()) { allApiMap.containsKey(it) }
                        val resolvedPins = pinnedp.map { pin ->
                            if (allApiMap.containsKey(pin)) {
                                pin
                            } else {
                                allApiMap.entries.firstOrNull { (key, api) ->
                                    pin == api.name && !claimedExactKeys.contains(key)
                                }?.let { (_, api) ->
                                    DataStoreHelper.getProviderPinKey(api.name, api.sourcePlugin)
                                } ?: pin
                            }
                        }
                        if (resolvedPins != pinnedp) {
                            DataStoreHelper.pinnedProviders = resolvedPins.toTypedArray()
                        }
                        pinnedp = resolvedPins
                    }
                    pinnedphashset = pinnedp.toHashSet()
                    arrayAdapter.clear()
                    val sortedApis = validAPIs
                        .filter {
                            val isPinned = DataStoreHelper.isProviderPinned(
                                pinnedphashset, it.name, it.sourcePlugin
                            )

                            // Hide pinned NSFW when NSFW not selected. NSFW is distracting when not chosen.
                            if (isPinned && !preSelectedTypes.contains(TvType.NSFW)) {
                                if (it.supportedTypes.all { type -> type == TvType.NSFW }) return@filter false
                            }

                            it.hasMainPage && (isPinned || it.supportedTypes.any(
                                preSelectedTypes::contains
                            ))
                        }
                        .sortedBy { it.name.lowercase() }

                    // Filter by the search query when set. None/Random and the currently
                    // selected provider always stay visible so the dialog can't get stuck.
                    val query = providerQuery.trim()
                    val queriedApis = if (query.isEmpty()) sortedApis else sortedApis.filter { api ->
                        val isFixed = api == noneApi || api == randomApi ||
                            (api.name == currentApiName &&
                                (api.sourcePlugin == currentApiSource || currentApiSource == null))
                        isFixed || getProviderSelectorLabel(api, isMultiLang)
                            .toString().contains(query, ignoreCase = true)
                    }

                    // Key by name + sourcePlugin so same-named providers from different
                    // plugins don't collapse into one entry ("Moviebox" from two repos).
                    val sortedApiMap = LinkedHashMap<String, MainAPI>().apply {
                        queriedApis.forEach {
                            put(DataStoreHelper.getProviderPinKey(it.name, it.sourcePlugin), it)
                        }
                    }

                    val pinnedApis = pinnedp.asReversed().mapNotNull { pin ->
                        sortedApiMap[pin]
                    }.distinct()

                    val remainingApis = queriedApis.filterNot { api ->
                        DataStoreHelper.isProviderPinned(pinnedphashset, api.name, api.sourcePlugin) &&
                            pinnedApis.any { it.name == api.name && it.sourcePlugin == api.sourcePlugin }
                    }

                    currentValidApis = mutableListOf<MainAPI>().apply {
                        addAll(validAPIs.take(2))
                        addAll(pinnedApis)
                        addAll(remainingApis)
                    }

                    val names = currentValidApis.map { api ->
                        getProviderSelectorLabel(api, isMultiLang)
                    }
                    val index = currentValidApis.indexOfFirst {
                        it.name == currentApiName && (currentApiSource == null || it.sourcePlugin == currentApiSource)
                    }.takeIf { it != -1 }
                        ?: -1
                    if (index >= 0) listView?.setItemChecked(index, true)
                    arrayAdapter.addAll(names)
                    arrayAdapter.notifyDataSetChanged()
                }
                fun togglePin(position: Int) {
                    if (currentValidApis.isEmpty() || position <= 1 || position >= currentValidApis.size) {
                        return
                    }
                    val pinnedp = DataStoreHelper.pinnedProviders.toMutableList()
                    val thisapi = currentValidApis[position]
                    val pinKey = DataStoreHelper.getProviderPinKey(thisapi.name, thisapi.sourcePlugin)
                    if (pinnedp.contains(pinKey)) {
                        pinnedp.remove(pinKey)
                    } else {
                        // Replace any legacy name-only pin with the name+source key so
                        // only this provider is pinned and the other same-named
                        // provider stays in the list (pin one "Moviebox" at a time).
                        pinnedp.remove(thisapi.name)
                        pinnedp.add(pinKey)
                    }
                    DataStoreHelper.pinnedProviders = pinnedp.toTypedArray()
                    updateList()
                }

                // pin provider on hold (phone)
                listView?.setOnItemLongClickListener { _, _, i, _ ->
                    togglePin(i)
                    true
                }

                // TV remotes cannot reliably deliver a ListView item long-click through the
                // D-pad center/OK button, so emulate a long press to pin/unpin the highlighted
                // provider while a short press still selects it. The framework's own key
                // long-press is disabled here to avoid toggling the pin twice.
                if (isLayout(TV or EMULATOR)) {
                    listView?.isLongClickable = false
                    val longPressHandler = Handler(Looper.getMainLooper())
                    val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
                    var longPressRunnable: Runnable? = null
                    var longPressTriggered = false
                    listView?.setOnKeyListener { _, keyCode, event ->
                        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                            keyCode != KeyEvent.KEYCODE_ENTER
                        ) {
                            return@setOnKeyListener false
                        }
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    longPressTriggered = false
                                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                                    val position = listView.selectedItemPosition
                                    val runnable = Runnable {
                                        longPressTriggered = true
                                        togglePin(position)
                                    }
                                    longPressRunnable = runnable
                                    longPressHandler.postDelayed(runnable, longPressTimeout)
                                }
                                false
                            }

                            KeyEvent.ACTION_UP -> {
                                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                                longPressRunnable = null
                                if (longPressTriggered) {
                                    longPressTriggered = false
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    }
                }

                bindChips(
                    binding.tvtypesChipsScroll.tvtypesChips,
                    preSelectedTypes,
                    validAPIs.flatMap { it.supportedTypes }.distinct()
                ) { list ->
                    preSelectedTypes.clear()
                    preSelectedTypes.addAll(list)
                    updateList()
                }

                // Live provider search
                binding.providerSearchInput.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        providerQuery = s?.toString() ?: ""
                        updateList()
                    }

                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                })

                // TV: pressing back from the search field should leave the field, not
                // close the dialog. Clearing focus returns D-pad focus to the list.
                binding.providerSearchInput.setOnFocusChangeListener { v, hasFocus ->
                    if (!hasFocus) {
                        hideKeyboard(v)
                    }
                }

                // Move focus/selection from the search field onto the provider list,
                // exactly like the on-screen keyboard's "Next" key does. The selector
                // (focus border) lands on the first row ("None") while the check mark
                // stays on the currently selected provider, and D-pad up/down then
                // moves the selector across the other plugins. Deferred until the
                // keyboard has hidden so the list can actually take focus.
                fun focusProviderList() {
                    hideKeyboard(binding.providerSearchInput)
                    binding.listview1.post {
                        binding.listview1.setSelection(0)
                        binding.listview1.requestFocus()
                    }
                }

                // The keyboard's Next/action key.
                binding.providerSearchInput.setOnEditorActionListener { _, _, _ ->
                    focusProviderList()
                    true
                }

                // The remote Back button: while the search field is focused it should
                // behave like Next (return to the list) instead of just dismissing the
                // keyboard/dialog with nothing selected. Back from the list still closes.
                builder.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK &&
                        binding.providerSearchInput.hasFocus() &&
                        (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
                    ) {
                        focusProviderList()
                        true
                    } else {
                        false
                    }
                }

                updateList()
            }
        }
    }

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val accountViewModel: AccountViewModel by activityViewModels()

    fun addMovies(cards: List<SearchResponse>) {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting addMovies")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")

                val programCards = cards

                TvChannelUtils.addPrograms(
                    context = ctx,
                    channelId = existingId,
                    items = programCards
                )
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding movies: $e")
        }
    }

    private fun deleteAll() {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting deleteAll")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")
                TvChannelUtils.deleteStoredPrograms(ctx)
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting programs: ${e.message}")
        }
    }

    override fun pickLayout(): Int? =
        if (isLayout(PHONE)) R.layout.fragment_home else R.layout.fragment_home_tv

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        (activity as? ComponentActivity)?.detachBackPressedCallback("HomeFragment_BackPress")
        bottomSheetDialog?.ownHide()
        super.onDestroyView()
    }

    private val apiChangeClickListener = View.OnClickListener { view ->
        view.context.selectHomepage(currentApiName, DataStoreHelper.currentHomeSource) { api ->
            homeViewModel.loadAndCancel(api, forceReload = true, fromUI = true)
        }
        /*val validAPIs = view.context?.filterProviderByPreferredMedia()?.toMutableList() ?: mutableListOf()

        validAPIs.add(0, randomApi)
        validAPIs.add(0, noneApi)
        view.popupMenuNoIconsAndNoStringRes(validAPIs.mapIndexed { index, api -> Pair(index, api.name) }) {
            homeViewModel.loadAndCancel(validAPIs[itemId].name)
        }*/
    }

    private var currentApiName: String? = null
    private var currentApiSource: String? = null
    private var toggleRandomButton = false

    private var bottomSheetDialog: BottomSheetDialog? = null
    private var homeMasterAdapter: HomeParentItemAdapterPreview? = null

    var lastSavedHomepage: String? = null

    fun saveHomepageToTV(page: Map<String, HomeViewModel.ExpandableHomepageList>) {
        // No need to update for phone
        if (isLayout(PHONE)) {
            return
        }
        val (name, data) = page.entries.firstOrNull() ?: return
        // Modifying homepage is an expensive operation, and therefore we avoid it at all cost
        if (name == lastSavedHomepage) {
            return
        }
        Log.i(TAG, "Adding programs $name to TV")
        lastSavedHomepage = name
        ioSafe {
            // empty the channel
            deleteAll()
            // insert the program from first array
            addMovies(data.list.list)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = false,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        configEvent.invoke()
    }

    @SuppressLint("SetTextI18n")
    override fun onBindingCreated(binding: FragmentHomeBinding) {
        context?.let { HomeChildItemAdapter.updatePosterSize(it) }
        (activity as? ComponentActivity)?.attachBackPressedCallback("HomeFragment_BackPress") {
            handleTvBackPress(this)
        }
        binding.apply {
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnLongClickListener {
                if (currentApiName == noneApi.name) return@setOnLongClickListener false
                homeViewModel.loadAndCancel(currentApiName, forceReload = true, fromUI = true)
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                true
            }
            homeChangeApi.setOnClickListener(apiChangeClickListener)
            homeSwitchAccount.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            homeMasterAdapter = HomeParentItemAdapterPreview(
                homeViewModel, accountViewModel
            )
            homeMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            homeMasterRecycler.adapter = homeMasterAdapter

            homeApiFab.isVisible = isLayout(PHONE)

            homePreviewReloadProvider.setOnClickListener {
                homeViewModel.loadAndCancel(
                    homeViewModel.apiName.value ?: noneApi.name,
                    forceReload = true,
                    fromUI = true
                )
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
            }

            homePreviewSearchButton.setOnClickListener { _ ->
                // Open blank screen.
                homeViewModel.queryTextSubmit("")
            }

            homePreviewSettingsButton.setOnClickListener { view ->
                val apiName = homeViewModel.apiName.value
                val plugin = APIHolder.getApiFromNameNull(apiName)
                    ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                val openSettings = plugin?.openSettings
                if (openSettings != null) {
                    try {
                        val activityContext = view.context.getActivity() ?: view.context
                        openSettings.invoke(activityContext)
                    } catch (e: Throwable) {
                        logError(e)
                    }
                }
            }

            // Load value for toggling Tv layout real time clock. Hide by default at startup
            // set visibility first, to apply a scroll effect later
            context?.let {
                if (isLayout(TV)) {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                    val toggleClock =
                        settingsManager.getBoolean(
                            getString(R.string.tv_layout_clock_key),
                            false
                        )
                    binding.homeClock.isVisible = toggleClock
                } else {
                    binding.homeClock.isVisible = false
                }
            }


            homeMasterRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (isLayout(PHONE)) {
                        // Fab is only relevant to Phone
                        if (dy > 0) { //check for scroll down
                            homeApiFab.shrink() // hide
                            homeRandom.shrink()
                        } else if (dy < -5) {
                            if (isLayout(PHONE)) {
                                homeApiFab.extend() // show
                                homeRandom.extend()
                            }
                        }
                    } else {
                        // Header scrolling is only relevant to TV/Emulator

                        val view = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                        val scrollParent = binding.homeApiHolder

                        if (view == null) {
                            // The first view is not laid out. When the list has content this
                            // means we scrolled past it, so hide the selector. But when the
                            // list is empty (e.g. the "None" provider) there is nothing to
                            // scroll past, so keep the selector visible and in place instead
                            // of removing it.
                            val hasContent = (recyclerView.adapter?.itemCount ?: 0) > 0
                            scrollParent.isVisible = !hasContent
                            if (!hasContent) scrollParent.translationY = 0f
                        } else {
                            // A bit weird, but this is a major limitation we are working around here
                            // 1. We cant have a real parent to the recyclerview as android cant layout that without lagging
                            // 2. We cant put the view in the recyclerview, as it should always be shown
                            // 3. We cant mirror the view in the recyclerview as then it causes focus issues when swaping out the mirror view
                            //
                            // This means that if we want to have a parent view to the recyclerview we are out of luck
                            // Instead this uses getLocationInWindow to calculate how much the view should be scrolled
                            // as recyclerView has no scrollY (always 0)
                            //
                            // Then it manually "scrolls" it to the correct position
                            //
                            // Hopefully getLocationInWindow acts correctly on all devices
                            val rect = IntArray(2)
                            view.getLocationInWindow(rect)
                            scrollParent.isVisible = true
                            scrollParent.translationY = rect[1].toFloat() - 60.toPx

                            // Move the TV layout real time clock out of the way too
                            // We check if we have the correct layout and if the clock is enabled
                            if(isLayout(TV) && binding.homeClock.isVisible) {
                                val scrollParent = binding.homeClock

                                val rect = IntArray(2)
                                view.getLocationInWindow(rect)
                                scrollParent.isVisible = true
                                scrollParent.translationY = rect[1].toFloat() - 60.toPx
                            }
                        }
                    }
                    super.onScrolled(recyclerView, dx, dy)
                    updateTvRailFocus()
                }
            })

            homeMasterRecycler.doOnLayout { updateTvRailFocus() }

        }

        //Load value for toggling Random button. Hide at startup
        context?.let {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
            toggleRandomButton =
                settingsManager.getBoolean(
                    getString(R.string.random_button_key),
                    false
                )
            binding.homeRandom.visibility = View.GONE
            binding.homeRandomButtonTv.visibility = View.GONE
        }

        observe(homeViewModel.apiName) { apiName ->
            currentApiName = apiName
            currentApiSource = DataStoreHelper.currentHomeSource
            val displayApiName = context?.getDisplayName(apiName) ?: apiName
            binding.apply {
                homeApiFab.text = displayApiName
                homeChangeApi.text = displayApiName
                homePreviewReloadProvider.isGone = (apiName == noneApi.name)
                homePreviewSearchButton.isGone = (apiName == noneApi.name)
            }
        }

        observe(homeViewModel.page) { data ->
            binding.apply {
                if (isLayout(TV or EMULATOR)) {
                    val plugin = APIHolder.getApiFromNameNull(homeViewModel.apiName.value)
                        ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                    homePreviewSettingsButton.isGone = plugin?.openSettings == null
                }
                
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.submitList(d.values.map {
                            it.copy(
                                list = it.list.copy(list = it.list.list.toMutableList())
                            )
                        })

                        saveHomepageToTV(d)

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isVisible = true
                        homeLoadingShimmer.stopShimmer()

                        //home_loaded?.isVisible = true
                        if (toggleRandomButton) {
                            val distinct = d.values
                                .flatMap { it.list.list }
                                .distinctBy { it.url }
                            val hasItems = distinct.isNotEmpty()
                            val isPhone = isLayout(PHONE)
                            val randomClickListener = View.OnClickListener {
                                distinct.randomOrNull()?.let { activity.loadSearchResult(it) }
                            }

                            homeRandom.isVisible = isPhone && hasItems
                            homeRandom.setOnClickListener(randomClickListener)
                            homeRandomButtonTv.isVisible = !isPhone && hasItems
                            homeRandomButtonTv.setOnClickListener(randomClickListener)
                        } else {
                            homeRandom.isGone = true
                            homeRandomButtonTv.isGone = true
                        }
                    }
                    //Open browser directly, without a menu.
                    is Resource.Failure -> {
                        homeLoadingShimmer.stopShimmer()
                        homeReloadConnectionerror.setOnClickListener(apiChangeClickListener)
                        homeReloadConnectionOpenInBrowser.setOnClickListener {
                            val currentApi = currentApiName?.let { getApiFromNameNull(it) }
                                ?: homeViewModel.apiName.value?.let { getApiFromNameNull(it) }
                            val mainUrl = currentApi?.mainUrl
                            if (!mainUrl.isNullOrBlank()) {
                                context?.openBrowser(mainUrl)
                            }
                        }

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = true
                        homeMasterRecycler.isInvisible = true

                        // Based on https://github.com/recloudstream/cloudstream/pull/1438
                        val hasNoNetworkConnection = context?.isNetworkAvailable() == false
                        val isNetworkError = data.isNetworkError

                        // Show the downloads button if we have any sort of network shenanigans
                        homeReloadConnectionGoToDownloads.isVisible =
                            hasNoNetworkConnection || isNetworkError

                        // Only hide the open in browser button if we know this is not network shenanigans related to cs3
                        homeReloadConnectionOpenInBrowser.isGone = hasNoNetworkConnection

                        resultErrorText.text = if (hasNoNetworkConnection) {
                            getString(R.string.no_internet_connection)
                        } else {
                            data.errorString
                        }

                        homeReloadConnectionGoToDownloads.setOnClickListener {
                            activity.navigate(R.id.navigation_downloads)
                        }

                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }

                    is Resource.Loading -> {
                        homeLoadingShimmer.startShimmer()
                        homeLoading.isVisible = true
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isInvisible = true
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                        //home_loaded?.isVisible = false
                    }
                }
            }
        }

        observeNullable(homeViewModel.popup) { item ->
            if (item == null) {
                bottomSheetDialog?.dismissSafe()
                bottomSheetDialog = null
                return@observeNullable
            }

            // don't recreate
            if (bottomSheetDialog != null) {
                return@observeNullable
            }

            val (items, delete) = item

            bottomSheetDialog = activity?.loadHomepageList(items, expandCallback = {
                homeViewModel.expandAndReturn(it)
            }, dismissCallback = {
                homeViewModel.popup(null)
                bottomSheetDialog = null
            }, deleteCallback = delete)
        }

        homeViewModel.reloadStored()
        homeViewModel.loadAndCancel(DataStoreHelper.currentHomePage, false)
        //loadHomePage(false)

        // nice profile pic on homepage
        //home_profile_picture_holder?.isVisible = false
        // just in case

        //TODO READD THIS
        /*for (syncApi in OAuth2Apis) {
            val login = SyncAPI2.loginInfo()
            val pic = login?.profilePicture
            if (home_profile_picture?.setImage(
                    pic,
                    errorImageDrawable = errorProfilePic
                ) == true
            ) {
                home_profile_picture_holder?.isVisible = true
                break
            }
        }*/
    }

    private fun updateTvRailFocus() {
        if (!isLayout(TV or EMULATOR)) return
        // home_api_holder is glued to the header row's top by the mirror logic in
        // the scroll listener above. Only target the provider button while the
        // holder is actually on screen; once the header scrolls off (y < 0) let
        // the rail fall back to default focus search so RIGHT enters content rows
        val holder = binding?.homeApiHolder
        val target =
            if (holder != null && holder.isVisible && holder.y >= 0f) R.id.home_change_api
            else View.NO_ID
        val rail = activity?.findViewById<View>(R.id.nav_rail_view) ?: return
        rail.nextFocusRightId = target
        for (focusView in arrayOf(
            R.id.navigation_downloads,
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_library,
            R.id.navigation_settings,
        )) {
            rail.findViewById<View>(focusView)?.nextFocusRightId = target
        }
    }

    private fun handleTvBackPress(helper: BackPressedCallbackHelper.CallbackHelper) {
        // Only apply custom behavior on TV interface
        if (!isLayout(TV)) {
            helper.runDefault()
            return
        }
        // When the selected provider page is empty (e.g. "None") there is no
        // focusable content, so currentFocus can be null. In that case recover
        // onto the plugin selector instead of falling through to default Back.
        val currentFocus = activity?.currentFocus ?: run {
            if (focusPluginSelector()) return
            helper.runDefault()
            return
        }
        // isInsideRecycle is true when focus is inside home_master_recycler
        var parent = currentFocus.parent
        var isInsideRecycler = false
        while (parent != null) {
            if (parent is View && parent.id == R.id.home_master_recycler) {
                isInsideRecycler = true
                break
            }
            parent = parent.parent
        }
        when {
            // Case 1: Focus is within plugin content -> Move to plugin selector
            isInsideRecycler -> {
                binding?.homeMasterRecycler?.scrollToPosition(0)
                focusPluginSelector()
            }
            // Case 2: Focus is on plugin selector or nearby buttons -> Move to home navigation
            currentFocus.id == R.id.home_change_api ||
            currentFocus.id == R.id.home_preview_reload_provider ||
            currentFocus.id == R.id.home_preview_search_button -> {
                activity?.findViewById<View>(R.id.navigation_home)?.requestFocus()
            }
            // Case 3: Any other location -> Use default back behavior
            else -> helper.runDefault()
        }
    }

    /**
     * Restores focus onto the plugin selector (home_change_api) so the focus
     * selector/bar that lets the user reach the dropdown of all plugin options
     * is visible again after Back. The selector is hidden while the header row
     * is scrolled off screen and focusing a hidden/offscreen view fails, so make
     * sure it is visible and reset its vertical mirror offset before focusing.
     */
    private fun focusPluginSelector(): Boolean {
        val holder = binding?.homeApiHolder ?: return false
        val selector = binding?.homeChangeApi ?: return false
        holder.isVisible = true
        holder.translationY = 0f
        selector.requestFocus()
        selector.post {
            if (!selector.hasFocus()) {
                holder.isVisible = true
                holder.translationY = 0f
                selector.requestFocus()
            }
        }
        return true
    }
}
