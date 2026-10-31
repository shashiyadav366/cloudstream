package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.databinding.HomeScrollViewBinding
import com.lagradost.cloudstream3.databinding.HomeScrollViewTvBinding
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

class HomeScrollAdapter(
    val callback: ((View, Int, LoadResponse) -> Unit)
) : RecyclerView.Adapter<ViewHolderState<Any>>() {
    companion object {
        // A large virtual list lets us reuse pages at both ends so the slider can wrap
        // with a normal single-page animation instead of scrolling through every item.
        private const val LOOPED_ITEM_COUNT = 100_000
    }

    var hasMoreItems: Boolean = false
    private var items: List<LoadResponse> = emptyList()

    fun submitList(list: Collection<LoadResponse>?) {
        items = list?.toList().orEmpty()
        notifyDataSetChanged()
    }

    fun getItemOrNull(position: Int): LoadResponse? = items.getOrNull(realPosition(position))

    fun realPosition(position: Int): Int =
        if (items.isEmpty()) 0 else position.mod(items.size)

    fun isLastItem(position: Int): Boolean =
        items.isNotEmpty() && realPosition(position) == items.lastIndex

    fun centeredPosition(realPosition: Int): Int {
        if (items.size <= 1) return 0
        val center = LOOPED_ITEM_COUNT / 2
        val base = center - center.mod(items.size)
        return base + realPosition.mod(items.size)
    }

    override fun getItemCount(): Int =
        if (items.size > 1) LOOPED_ITEM_COUNT else items.size

    private fun itemAt(position: Int): LoadResponse =
        items[position.mod(items.size)]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) {
            HomeScrollViewTvBinding.inflate(inflater, parent, false)
        } else {
            HomeScrollViewBinding.inflate(inflater, parent, false)
        }

        return ViewHolderState(binding)
    }

    override fun onBindViewHolder(holder: ViewHolderState<Any>, position: Int) {
        onBindContent(holder, itemAt(position), realPosition(position))
    }

    override fun onViewRecycled(holder: ViewHolderState<Any>) {
        onClearView(holder)
        super.onViewRecycled(holder)
    }

    private fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is HomeScrollViewBinding -> {
                BaseAdapter.clearImage(binding.homeScrollPreview)
            }

            is HomeScrollViewTvBinding -> {
                BaseAdapter.clearImage(binding.homeScrollPreview)
            }
        }
    }

    private fun onBindContent(
        holder: ViewHolderState<Any>,
        item: LoadResponse,
        position: Int,
    ) {
        val binding = holder.view

        val posterUrl = item.backgroundPosterUrl ?: item.posterUrl

        when (binding) {
            is HomeScrollViewBinding -> {
                binding.homeScrollPreview.setOnClickListener { view ->
                    callback.invoke(view, position, item)
                }
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders)
                binding.homeScrollPreviewTags.apply {
                    text = item.tags?.joinToString(" • ") ?: ""
                    isGone = item.tags.isNullOrEmpty()
                    maxLines = 2
                }
                binding.homeScrollPreviewTitle.text = item.name.html()

                bindLogo(
                    url = item.logoUrl,
                    headers = item.posterHeaders,
                    titleView = binding.homeScrollPreviewTitle,
                    logoView = binding.homePreviewLogo
                )
            }

            is HomeScrollViewTvBinding -> {
                binding.homeScrollPreview.isFocusable = false
                binding.homeScrollPreview.setOnClickListener { view ->
                    callback.invoke(view, position, item)
                }
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders)
            }
        }
    }
}
