package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationBarItemView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentHomeHeadBinding
import com.lagradost.cloudstream3.databinding.FragmentHomeHeadTvBinding
import com.lagradost.cloudstream3.getSourcePlugin
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountEditDialog
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showOptionSelectStringRes
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbarMargin
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbarView
import com.lagradost.cloudstream3.utils.UIHelper.populateChips
import androidx.core.graphics.toColorInt
import com.lagradost.cloudstream3.ui.setRecycledViewPool

private const val SLIDER_AUTO_SLIDE_INTERVAL_MS = 5_000L

class HomeParentItemAdapterPreview(
    private val viewModel: HomeViewModel,
    private val accountViewModel: AccountViewModel
) : ParentItemAdapter(
    id = "HomeParentItemAdapterPreview".hashCode(),
    clickCallback = {
        viewModel.click(it)
    }, moreInfoClickCallback = {
        viewModel.popup(it)
    }, expandCallback = {
        viewModel.expand(it)
    }) {
    override val headers = 1
    override fun onCreateHeader(parent: ViewGroup): ViewHolderState<Bundle> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) FragmentHomeHeadTvBinding.inflate(
            inflater,
            parent,
            false
        ) else FragmentHomeHeadBinding.inflate(inflater, parent, false)

        if (binding is FragmentHomeHeadTvBinding && isLayout(EMULATOR)) {
            binding.homeBookmarkParentItemMoreInfo.isVisible = true

            val marginInDp = 50
            val density = binding.horizontalScrollChips.context.resources.displayMetrics.density
            val marginInPixels = (marginInDp * density).toInt()

            val params = binding.horizontalScrollChips.layoutParams as ViewGroup.MarginLayoutParams
            params.marginEnd = marginInPixels
            binding.horizontalScrollChips.layoutParams = params
            binding.homeWatchParentItemTitle.setCompoundDrawablesWithIntrinsicBounds(
                null,
                null,
                ContextCompat.getDrawable(
                    parent.context,
                    R.drawable.ic_baseline_arrow_forward_24
                ),
                null
            )
        }

        return HeaderViewHolder(binding, viewModel, accountViewModel)
    }

    override fun onBindHeader(holder: ViewHolderState<Bundle>) {
        (holder as? HeaderViewHolder)?.bind()
    }

    override fun onViewDetachedFromWindow(holder: ViewHolderState<Bundle>) {
        when (holder) {
            is HeaderViewHolder -> {
                holder.onViewDetachedFromWindow()
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolderState<Bundle>) {
        when (holder) {
            is HeaderViewHolder -> {
                holder.onViewAttachedToWindow()
            }
        }
    }

    private class HeaderViewHolder(
        val binding: ViewBinding,
        val viewModel: HomeViewModel,
        accountViewModel: AccountViewModel,
    ) :
        ViewHolderState<Bundle>(binding) {

        override fun save(): Bundle =
            Bundle().apply {
                putParcelable(
                    "resumeRecyclerView",
                    resumeRecyclerView.layoutManager?.onSaveInstanceState()
                )
                putParcelable(
                    "bookmarkRecyclerView",
                    bookmarkRecyclerView.layoutManager?.onSaveInstanceState()
                )
                //putInt("previewViewpager", previewViewpager.currentItem)
            }

        override fun restore(state: Bundle) {
            state.getSafeParcelable<Parcelable>("resumeRecyclerView")?.let { recycle ->
                resumeRecyclerView.layoutManager?.onRestoreInstanceState(recycle)
            }
            state.getSafeParcelable<Parcelable>("bookmarkRecyclerView")?.let { recycle ->
                bookmarkRecyclerView.layoutManager?.onRestoreInstanceState(recycle)
            }
        }

        val previewAdapter = HomeScrollAdapter { view, position, item ->
            viewModel.click(
                LoadClickCallback(0, view, position, item)
            )
        }

        private var latestPreview: Resource<Pair<Boolean, List<LoadResponse>>>? = null
        private var livePreviewItems: List<LoadResponse> = emptyList()
        private var showingLivePreview = false

        private fun moveSlider(offset: Int) {
            val count = previewAdapter.itemCount
            if (count <= 1) return
            val next = (previewViewpager.currentItem + offset).coerceIn(0, count - 1)
            if (next != previewViewpager.currentItem) {
                previewViewpager.setCurrentItem(next, true)
            }
        }

        private val autoSlideRunnable = object : Runnable {
            override fun run() {
                moveSlider(1)
                if (itemView.isAttachedToWindow) {
                    itemView.postDelayed(this, SLIDER_AUTO_SLIDE_INTERVAL_MS)
                }
            }
        }

        private fun startAutoSlide() {
            itemView.removeCallbacks(autoSlideRunnable)
            itemView.postDelayed(autoSlideRunnable, SLIDER_AUTO_SLIDE_INTERVAL_MS)
        }

        private fun stopAutoSlide() {
            itemView.removeCallbacks(autoSlideRunnable)
        }

        private val resumeAdapter = ResumeItemAdapter(
            nextFocusUp = itemView.nextFocusUpId,
            nextFocusDown = itemView.nextFocusDownId,
            removeCallback = { v ->
                try {
                    val context = v.context ?: return@ResumeItemAdapter
                    val builder: AlertDialog.Builder =
                        AlertDialog.Builder(context)
                    // Copy pasted from https://github.com/recloudstream/cloudstream/pull/1658/files
                    builder.apply {
                        setTitle(R.string.clear_history)
                        setMessage(
                            context.getString(R.string.delete_message).format(
                                context.getString(
                                    R.string.continue_watching
                                )
                            )
                        )
                        setNegativeButton(R.string.cancel) { _, _ -> /*NO-OP*/ }
                        setPositiveButton(R.string.delete) { _, _ ->
                            DataStoreHelper.deleteAllWatchHistory()
                            viewModel.reloadStored()
                        }
                        show().setDefaultFocus()
                    }
                } catch (t: Throwable) {
                    // This may throw a formatting error
                    logError(t)
                }
            },
            clickCallback = { callback ->
                if (callback.action != SEARCH_ACTION_SHOW_METADATA) {
                    viewModel.click(callback)
                    return@ResumeItemAdapter
                }

                // The touch popup cannot be opened from a TV remote, so the same
                // options are offered as a D-pad friendly dialog instead.
                if (isLayout(TV or EMULATOR)) {
                    showResumeCardMenu(callback.view.context) { option ->
                        when (option) {
                            ResumeCardOption.Play -> viewModel.click(
                                SearchClickCallback(
                                    START_ACTION_RESUME_LATEST,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )

                            ResumeCardOption.MoreInfo -> viewModel.click(
                                SearchClickCallback(
                                    SEARCH_ACTION_LOAD,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )

                            ResumeCardOption.Remove -> {
                                val card = callback.card
                                if (card is DataStoreHelper.ResumeWatchingResult) {
                                    DataStoreHelper.removeResumeWatching(
                                        card.parentId,
                                        card.id,
                                        card.url,
                                        card.sourcePlugin
                                    )
                                    viewModel.reloadStored()
                                }
                            }
                        }
                    }
                    return@ResumeItemAdapter
                }

                callback.view.context?.getActivity()?.showOptionSelectStringRes(
                    callback.view,
                    callback.card.posterUrl,
                    listOf(
                        R.string.action_open_watching,
                        R.string.action_remove_watching
                    ),
                    listOf(
                        R.string.action_open_play,
                        R.string.action_open_watching,
                        R.string.action_remove_watching
                    )
                ) { (isTv, actionId) ->
                    when (actionId + if (isTv) 0 else 1) {
                        // play
                        0 -> {
                            viewModel.click(
                                SearchClickCallback(
                                    START_ACTION_RESUME_LATEST,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )
                        }
                        //info
                        1 -> {
                            viewModel.click(
                                SearchClickCallback(
                                    SEARCH_ACTION_LOAD,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )
                        }
                        // remove
                        2 -> {
                            val card = callback.card
                            if (card is DataStoreHelper.ResumeWatchingResult) {
                                DataStoreHelper.removeResumeWatching(
                                    card.parentId,
                                    card.id,
                                    card.url,
                                    card.sourcePlugin
                                )
                                viewModel.reloadStored()
                            }
                        }
                    }
                }
            })
        private val cricketAdapter = HomeChildItemAdapter(
            id = "cricketLiveAdapter".hashCode(),
            nextFocusUp = itemView.nextFocusUpId,
            nextFocusDown = itemView.nextFocusDownId,
            clickCallback = { callback -> viewModel.click(callback) }
        ).apply {
            isHorizontal = true
            // Live cricket cards are 20% smaller than the poster scale.
            landscapeScale = 0.8f
        }

        private val bookmarkAdapter = HomeChildItemAdapter(
            id = "bookmarkAdapter".hashCode(),
            nextFocusUp = itemView.nextFocusUpId,
            nextFocusDown = itemView.nextFocusDownId
        ) { callback ->
            if (callback.action != SEARCH_ACTION_SHOW_METADATA) {
                viewModel.click(callback)
                return@HomeChildItemAdapter
            }

            (callback.view.context?.getActivity() as? MainActivity)?.loadPopup(
                callback.card,
                load = false
            )
            /*
            callback.view.context?.getActivity()?.showOptionSelectStringRes(
                callback.view,
                callback.card.posterUrl,
                listOf(
                    R.string.action_open_watching,
                    R.string.action_remove_from_bookmarks,
                ),
                listOf(
                    R.string.action_open_play,
                    R.string.action_open_watching,
                    R.string.action_remove_from_bookmarks
                )
            ) { (isTv, actionId) ->
                when (actionId + if (isTv) 0 else 1) { // play
                    0 -> {
                        viewModel.click(
                            SearchClickCallback(
                                START_ACTION_RESUME_LATEST,
                                callback.view,
                                -1,
                                callback.card
                            )
                        )
                    }

                    1 -> { // info
                        viewModel.click(
                            SearchClickCallback(
                                SEARCH_ACTION_LOAD,
                                callback.view,
                                -1,
                                callback.card
                            )
                        )
                    }

                    2 -> { // remove
                        DataStoreHelper.setResultWatchState(
                            callback.card.id,
                            WatchType.NONE.internalId
                        )
                        viewModel.reloadStored()
                    }
                }
            }
            */
        }
private val previewViewpager: ViewPager2 =
    itemView.findViewById(R.id.home_preview_viewpager)

private val previewViewpagerText: ViewGroup =
    itemView.findViewById(R.id.home_preview_viewpager_text)

// private val previewHeader: FrameLayout = itemView.findViewById(R.id.home_preview)
private val resumeHolder: View =
    itemView.findViewById(R.id.home_watch_holder)

private val homePreviewShimmer: com.facebook.shimmer.ShimmerFrameLayout? =
    itemView.findViewById(R.id.home_preview_shimmer)

private val resumeRecyclerView: RecyclerView =
    itemView.findViewById(R.id.home_watch_child_recyclerview)

private val cricketHolder: View =
    itemView.findViewById(R.id.home_cricket_holder)

private val cricketRecyclerView: RecyclerView =
    itemView.findViewById(R.id.home_cricket_child_recyclerview)

private val bookmarkHolder: View =
    itemView.findViewById(R.id.home_bookmarked_holder)

private val bookmarkRecyclerView: RecyclerView =
    itemView.findViewById(R.id.home_bookmarked_child_recyclerview)

private val headProfilePic: ImageView? =
    itemView.findViewById(R.id.home_head_profile_pic)

private val headProfilePicCard: View? =
    itemView.findViewById(R.id.home_head_profile_padding)

private val headReloadProvider: View? =
    itemView.findViewById(R.id.home_head_reload_provider)

private val alternateHeadReloadProvider: View? =
    itemView.findViewById(R.id.alternate_home_head_reload_provider)

private val alternateHeadProfilePic: ImageView? =
    itemView.findViewById(R.id.alternate_home_head_profile_pic)

private val alternateHeadProfilePicCard: View? =
    itemView.findViewById(R.id.alternate_home_head_profile_padding)

private val topPadding: View? =
    itemView.findViewById(R.id.home_padding)

private val alternativeAccountPadding: View? =
    itemView.findViewById(R.id.alternative_account_padding)

private val homeNonePadding: View =
    itemView.findViewById(R.id.home_none_padding)
        
        fun onSelect(item: LoadResponse, position: Int) {
            (binding as? FragmentHomeHeadTvBinding)?.apply {
                homePreviewDescription.isGone = item.plot.isNullOrBlank()
                homePreviewDescription.text = item.plot?.html() ?: ""

                val scoreText = item.score?.toStringNull(0.1, 10, 1, false)

                scoreText?.let { score ->
                    homePreviewScore.text =
                        homePreviewScore.context.getString(R.string.extension_rating, score)

                    // while it should never fail, we do this just in case
                    val rating = score.toDoubleOrNull() ?: item.score?.toDouble() ?: 0.0

                    val color = when {
                        rating < 5.0 -> "#eb2f2f".toColorInt() // Red
                        rating < 8.0 -> "#eda009".toColorInt() // Yellow
                        else -> "#3bb33b".toColorInt() // Green
                    }
                    homePreviewScore.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(color)
                }
                homePreviewScore.isGone = scoreText == null

                item.year?.let { year ->
                    homePreviewYear.text = year.toString()
                }
                homePreviewYear.isGone = item.year == null

                val duration = item.duration
                duration?.let { min ->
                    homePreviewDuration.text =
                        homePreviewDuration.context.getString(R.string.duration_format, min)
                }
                homePreviewDuration.isGone = duration == null || duration <= 0

                val castText = item.actors?.take(3)?.joinToString(", ") { it.actor.name }
                if (!castText.isNullOrBlank()) {
                    homePreviewCast.text =
                        homePreviewCast.context.getString(R.string.cast_format, castText)
                    homePreviewCast.isVisible = true
                } else {
                    homePreviewCast.isVisible = false
                }

                homePreviewText.text = item.name.html()
                populateChips(
                    homePreviewTags,
                    item.tags?.take(6) ?: emptyList(),
                    R.style.ChipFilledSemiTransparent,
                    null
                )


                bindLogo(
                    url = item.logoUrl,
                    headers = item.posterHeaders,
                    titleView = homePreviewText,
                    logoView = homeBackgroundPosterWatermarkBadgeHolder
                )

                homePreviewTags.isGone =
                    item.tags.isNullOrEmpty()

                homePreviewInfoBtt.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(0, view, position, item)
                    )
                }
            }
            (binding as? FragmentHomeHeadBinding)?.apply {
                //homePreviewImage.setImage(item.posterUrl, item.posterHeaders)

                homePreviewPlay.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(
                            START_ACTION_RESUME_LATEST,
                            view,
                            position,
                            item
                        )
                    )
                }

                homePreviewInfo.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(0, view, position, item)
                    )
                }

                // very ugly code, but I don't care
                val id = item.getId()
                val watchType =
                    DataStoreHelper.getResultWatchState(id)
                homePreviewBookmark.setText(watchType.stringRes)
                homePreviewBookmark.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(
                        homePreviewBookmark.context,
                        watchType.iconRes
                    ),
                    null,
                    null
                )

                homePreviewBookmark.setOnClickListener { fab ->
                    fab.context.getActivity()?.showBottomDialog(
                        WatchType.entries
                            .map { fab.context.getString(it.stringRes) }
                            .toList(),
                        DataStoreHelper.getResultWatchState(id).ordinal,
                        fab.context.getString(R.string.action_add_to_bookmarks),
                        showApply = false,
                        {}) {
                        val newValue = WatchType.entries[it]

                        ResultViewModel2().updateWatchStatus(
                            newValue,
                            fab.context,
                            item
                        ) { statusChanged: Boolean ->
                            if (!statusChanged) return@updateWatchStatus

                            homePreviewBookmark.setCompoundDrawablesWithIntrinsicBounds(
                                null,
                                ContextCompat.getDrawable(
                                    homePreviewBookmark.context,
                                    newValue.iconRes
                                ),
                                null,
                                null
                            )
                            homePreviewBookmark.setText(newValue.stringRes)
                        }
                    }
                }
            }
        }

        private val previewCallback: ViewPager2.OnPageChangeCallback =
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val realPosition = previewAdapter.realPosition(position)
                    if (previewAdapter.isLastItem(position) && previewAdapter.hasMoreItems) {
                        previewAdapter.hasMoreItems = false // don't make two requests
                        viewModel.loadMoreHomeScrollResponses()
                    }
                    val item = previewAdapter.getItemOrNull(position) ?: return
                    onSelect(item, realPosition)
                }
            }

        fun onViewDetachedFromWindow() {
            stopAutoSlide()
            previewViewpager.unregisterOnPageChangeCallback(previewCallback)
        }

        private val toggleList = listOf<Pair<Chip, WatchType>>(
            Pair(itemView.findViewById(R.id.home_type_watching_btt), WatchType.WATCHING),
            Pair(itemView.findViewById(R.id.home_type_completed_btt), WatchType.COMPLETED),
            Pair(itemView.findViewById(R.id.home_type_dropped_btt), WatchType.DROPPED),
            Pair(itemView.findViewById(R.id.home_type_on_hold_btt), WatchType.ONHOLD),
            Pair(itemView.findViewById(R.id.home_plan_to_watch_btt), WatchType.PLANTOWATCH),
        )

        private val toggleListHolder: ChipGroup? = itemView.findViewById(R.id.home_type_holder)

        fun bind() {
    headReloadProvider?.setOnClickListener {
        viewModel.reloadHomeFromUI()
        showToast(R.string.action_reload, Toast.LENGTH_SHORT)
    }
}
        init {
            previewViewpager.setPageTransformer(HomeScrollTransformer())

            previewViewpager.adapter = previewAdapter
            resumeRecyclerView.adapter = resumeAdapter
            cricketRecyclerView.adapter = cricketAdapter
            bookmarkRecyclerView.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
            bookmarkRecyclerView.adapter = bookmarkAdapter

            cricketRecyclerView.setLinearListLayout(
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )

            resumeRecyclerView.setLinearListLayout(
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )

            bookmarkRecyclerView.setLinearListLayout(
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )

            fixPaddingStatusbarMargin(topPadding)

            for ((chip, watch) in toggleList) {
                chip.isChecked = false
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        viewModel.loadStoredData(setOf(watch))
                    }
                    // Else if all are unchecked -> Do not load data
                    else if (toggleList.all { !it.first.isChecked }) {
                        viewModel.loadStoredData(emptySet())
                    }
                }
            }

            headProfilePicCard?.isGone = isLayout(TV or EMULATOR)
            alternateHeadProfilePicCard?.isGone = isLayout(TV or EMULATOR)

            (headProfilePic ?: alternateHeadProfilePic)?.observe(viewModel.currentAccount) { currentAccount ->
                headProfilePic?.loadImage(currentAccount?.image)
                alternateHeadProfilePic?.loadImage(currentAccount?.image)
            }

            headProfilePicCard?.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            // The hero preview header (search, reload, profile) is hidden when the
            // selected provider has no preview — custom merged sources have none —
            // so the fallback header needs its own reload action.
            alternateHeadReloadProvider?.setOnClickListener {
                viewModel.reloadHomeFromUI()
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
            }

            fun showAccountEditBox(context: Context): Boolean {
                val currentAccount = DataStoreHelper.getCurrentAccount()
                return if (currentAccount != null) {
                    showAccountEditDialog(
                        context = context,
                        account = currentAccount,
                        isNewAccount = false,
                        accountEditCallback = { accountViewModel.handleAccountUpdate(it, context) },
                        accountDeleteCallback = {
                            accountViewModel.handleAccountDelete(
                                it,
                                context
                            )
                        }
                    )
                    true
                } else false
            }

            alternateHeadProfilePicCard?.setOnLongClickListener {
                showAccountEditBox(it.context)
            }
            headProfilePicCard?.setOnLongClickListener {
                showAccountEditBox(it.context)
            }

            alternateHeadProfilePicCard?.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            (binding as? FragmentHomeHeadTvBinding)?.apply {
                /*homePreviewChangeApi.setOnClickListener { view ->
                    view.context.selectHomepage(viewModel.repo?.name) { api ->
                        viewModel.loadAndCancel(api, forceReload = true, fromUI = true)
                    }
                }
                homePreviewReloadProvider.setOnClickListener {
                    viewModel.reloadHomeFromUI()
                    showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                    true
                }
                homePreviewSearchButton.setOnClickListener { _ ->
                    // Open blank screen.
                    viewModel.queryTextSubmit("")
                }*/

                // A workaround to the focus problem of always centering the view on focus
                // as that causes higher android versions to stretch the ui when switching between shows
                var lastFocusTimeoutMs = 0L
                homePreviewInfoBtt.setOnFocusChangeListener { view, hasFocus ->
                    val lastFocusMs = lastFocusTimeoutMs
                    // Always reset timer, as we only want to update
                    // it if we have not interacted in half a second
                    lastFocusTimeoutMs = System.currentTimeMillis()
                    if (!hasFocus) return@setOnFocusChangeListener
                    if (lastFocusMs + 500L < System.currentTimeMillis()) {
                        MainActivity.centerView(view)
                    }
                }

                homePreviewHiddenNextFocus.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) return@setOnFocusChangeListener
                    moveSlider(1)
                    homePreviewInfoBtt.requestFocus()
                }

                homePreviewHiddenPrevFocus.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) return@setOnFocusChangeListener
                    if (previewViewpager.currentItem <= 0) {
                        //Focus the Home item as the default focus will be the header item
                        (activity as? MainActivity)?.binding?.navRailView?.findViewById<NavigationBarItemView>(
                            R.id.navigation_home
                        )?.requestFocus()
                    } else {
                        previewViewpager.setCurrentItem(previewViewpager.currentItem - 1, true)
                        binding.homePreviewInfoBtt.requestFocus()
                        //binding.homePreviewPlayBtt.requestFocus()
                    }
                }
            }

            (binding as? FragmentHomeHeadBinding)?.apply {
                homeSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        viewModel.queryTextSubmit(query)
                        return true
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        viewModel.queryTextChange(newText)
                        return true
                    }
                })
            }
        }

        private fun SearchResponse.toSliderResponse(): LoadResponse =
            ResultViewModel2.LoadResponseFromSearch(
                name = name,
                url = url,
                apiName = apiName,
                type = type ?: TvType.Live,
                posterUrl = posterUrl,
                score = score,
                posterHeaders = posterHeaders,
                id = id,
                sourcePlugin = getSourcePlugin(),
            )

        private fun updatePreview(preview: Resource<Pair<Boolean, List<LoadResponse>>>) {
            latestPreview = preview
            renderSlider()
        }

        private fun renderSlider() {
            if (livePreviewItems.isNotEmpty()) {
                showSlider(Resource.Success(false to livePreviewItems), isLive = true)
            } else {
                latestPreview?.let { showSlider(it, isLive = false) }
            }
        }

        private fun showSlider(
            preview: Resource<Pair<Boolean, List<LoadResponse>>>,
            isLive: Boolean,
        ) {
            val resetToFirst = showingLivePreview != isLive
            if (resetToFirst) {
                showingLivePreview = isLive
            }
            if (preview is Resource.Success) {
                homeNonePadding.apply {
                    val params = layoutParams
                    params.height = 0
                    layoutParams = params
                }
            } else fixPaddingStatusbarView(homeNonePadding)

            when (preview) {
                is Resource.Success -> {
                    homePreviewShimmer?.stopShimmer()
                    homePreviewShimmer?.isGone = true
                    val items = preview.value.second
                    val previousPosition =
                        previewAdapter.realPosition(previewViewpager.currentItem)
                    previewAdapter.submitList(items)
                    previewAdapter.hasMoreItems = preview.value.first && !isLive
                    /*if (!.setItems(
                            preview.value.second,
                            preview.value.first
                        )
                    ) {
                        // this might seam weird and useless, however this prevents a very weird andrid bug were the viewpager is not rendered properly
                        // I have no idea why that happens, but this is my ducktape solution
                        previewViewpager.setCurrentItem(0, false)
                        previewViewpager.beginFakeDrag()
                        previewViewpager.fakeDragBy(1f)
                        previewViewpager.endFakeDrag()
                        previewCallback.onPageSelected(0)
                        //previewHeader.isVisible = true
                    }*/

                    previewViewpager.isVisible = true
                    previewViewpagerText.isVisible = true
                    alternativeAccountPadding?.isVisible = false
                    (binding as? FragmentHomeHeadTvBinding)?.apply {
                        homePreviewInfoBtt.isVisible = true
                    }
                    // Explicitly bind the current item to ensure instant loading
                    if (items.isNotEmpty()) {
                        val currentPos = if (resetToFirst) {
                            0
                        } else {
                            previousPosition.coerceIn(0, items.lastIndex)
                        }
                        val target = previewAdapter.centeredPosition(currentPos)
                        if (previewViewpager.currentItem != target) {
                            previewViewpager.setCurrentItem(target, false)
                        }
                        onSelect(items[currentPos], currentPos)
                    }
                }

                is Resource.Loading -> {
                    previewViewpager.isGone = true
                    previewViewpagerText.isGone = true
                    homePreviewShimmer?.isVisible = true
                    homePreviewShimmer?.startShimmer()
                }

                else -> {
                    homePreviewShimmer?.stopShimmer()
                    homePreviewShimmer?.isGone = true
                    previewAdapter.submitList(listOf())
                    previewViewpager.setCurrentItem(0, false)
                    previewViewpager.isVisible = false
                    previewViewpagerText.isVisible = false
                    alternativeAccountPadding?.isVisible = true
                    (binding as? FragmentHomeHeadTvBinding)?.apply {
                        homePreviewInfoBtt.isVisible = false
                    }
                    //previewHeader.isVisible = false
                }
            }
        }

        private fun updateCricketLive(matches: List<SearchResponse>) {
            val show = matches.isNotEmpty()
            cricketHolder.isVisible = show
            cricketAdapter.submitList(matches)

            // Only matches that are streaming right now go in the hero slider. The row
            // keeps today's scheduled events too. Clearing the live list falls back to
            // the provider preview that is kept in latestPreview.
            livePreviewItems = matches
                .filter { LiveEventNotifier.isCurrentlyLive(it) }
                .map { it.toSliderResponse() }
            renderSlider()

            val cricketTitle =
                (binding as? FragmentHomeHeadBinding)?.homeCricketParentItemTitle
                    ?: (binding as? FragmentHomeHeadTvBinding)?.homeCricketParentItemTitle
            cricketTitle?.setOnClickListener {
                if (matches.isEmpty()) return@setOnClickListener
                viewModel.popup(
                    HomeViewModel.ExpandableHomepageList(
                        HomePageList(
                            cricketTitle.text.toString(),
                            matches,
                            true
                        ), 1, false
                    )
                )
            }

            if (isLayout(TV or EMULATOR)) {
                // Keep d-pad navigation in visual order on TV: watch -> cricket -> bookmarks.
                resumeRecyclerView.nextFocusDownId = if (show) {
                    R.id.home_cricket_child_recyclerview
                } else {
                    R.id.home_type_holder
                }
            }
        }

        private fun updateResume(resumeWatching: List<SearchResponse>) {
            resumeHolder.isVisible = resumeWatching.isNotEmpty()
            resumeAdapter.submitList(resumeWatching)

            if (
                binding is FragmentHomeHeadBinding ||
                binding is FragmentHomeHeadTvBinding &&
                isLayout(EMULATOR)
            ) {
                val title = (binding as? FragmentHomeHeadBinding)?.homeWatchParentItemTitle
                    ?: (binding as? FragmentHomeHeadTvBinding)?.homeWatchParentItemTitle

                title?.setOnClickListener {
                    viewModel.popup(
                        HomeViewModel.ExpandableHomepageList(
                            HomePageList(
                                title.text.toString(),
                                resumeWatching,
                                false
                            ), 1, false
                        ),
                        deleteCallback = {
                            viewModel.deleteResumeWatching()
                        }
                    )
                }
            }
        }

        private fun updateBookmarks(data: Pair<Boolean, List<SearchResponse>>) {
            val (visible, list) = data
            bookmarkHolder.isVisible = visible
            bookmarkAdapter.submitList(list)

            if (
                binding is FragmentHomeHeadBinding ||
                binding is FragmentHomeHeadTvBinding &&
                isLayout(EMULATOR)
            ) {
                val title = (binding as? FragmentHomeHeadBinding)?.homeBookmarkParentItemTitle
                    ?: (binding as? FragmentHomeHeadTvBinding)?.homeBookmarkParentItemTitle

                title?.setOnClickListener {
                    val items = toggleList.map { it.first }.filter { it.isChecked }
                    if (items.isEmpty()) return@setOnClickListener // we don't want to show an empty dialog
                    val textSum = items
                        .mapNotNull { it.text }.joinToString()

                    viewModel.popup(
                        HomeViewModel.ExpandableHomepageList(
                            HomePageList(
                                textSum,
                                list,
                                false
                            ), 1, false
                        ), deleteCallback = {
                            viewModel.deleteBookmarks(list)
                        }
                    )
                }
            }
        }

        fun onViewAttachedToWindow() {
            previewViewpager.registerOnPageChangeCallback(previewCallback)
            startAutoSlide()

            previewViewpager.apply {
                observe(viewModel.preview) {
                    updatePreview(it)
                }
                /*if (binding is FragmentHomeHeadTvBinding) {
                    observe(viewModel.apiName) { name ->
                        binding.homePreviewChangeApi.text = name
                        binding.homePreviewReloadProvider.isGone = (name == noneApi.name)
                    }
                }*/
                observe(viewModel.resumeWatching) {
                    updateResume(it)
                }
                observe(viewModel.cricketLive) {
                    updateCricketLive(it)
                }
                observe(viewModel.bookmarks) {
                    updateBookmarks(it)
                }
                observe(viewModel.availableWatchStatusTypes) { (checked, visible) ->
                    for ((chip, watch) in toggleList) {
                        chip.apply {
                            isVisible = visible.contains(watch)
                            isChecked = checked.contains(watch)
                        }
                    }
                    toggleListHolder?.isGone = visible.isEmpty()
                }
            }
        }
    }
}

private enum class ResumeCardOption { Play, MoreInfo, Remove }

/**
 * Options for a single Continue Watching card. Uses a plain list dialog so it
 * stays usable with a D-pad, unlike the touch popup used on phones.
 */
private fun showResumeCardMenu(
    context: Context,
    onSelected: (ResumeCardOption) -> Unit,
) {
    val options = listOf(
        ResumeCardOption.Play to context.getString(R.string.action_open_play),
        ResumeCardOption.MoreInfo to context.getString(R.string.action_open_watching),
        ResumeCardOption.Remove to context.getString(R.string.action_remove_watching),
    )
    AlertDialog.Builder(context)
        .setTitle(R.string.continue_watching)
        .setItems(options.map { it.second }.toTypedArray()) { _, which ->
            onSelected(options[which].first)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
        .setDefaultFocus()
}
