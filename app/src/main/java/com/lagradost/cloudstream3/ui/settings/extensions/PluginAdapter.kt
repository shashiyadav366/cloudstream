package com.lagradost.cloudstream3.ui.settings.extensions

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.google.android.material.color.MaterialColors
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.RepositoryItemBinding
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.txt
import java.text.DecimalFormat
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class PluginViewData(
    val pluginWrapper: PluginWrapper,
    val isDownloaded: Boolean,
)

class RepositoryViewHolderState(view: ViewBinding) : ViewHolderState<Any>(view) {
    // Store how many times this has called recycled, this is used to correctly sync text in jobs
    var recycleCount = 0
}

class PluginAdapter(
    val showRepositoryNames: Boolean = false,
    /** Only the per-repository plugins list shows the ignore (skip list) button. */
    val showIgnoreButton: Boolean = false,
    val iconClickCallback: (PluginWrapper) -> Unit,
    val ignoreClickCallback: (PluginWrapper) -> Unit = {},
) : NoStateAdapter<PluginViewData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.pluginWrapper.plugin.internalName == b.pluginWrapper.plugin.internalName && a.pluginWrapper.repositoryData.url == b.pluginWrapper.repositoryData.url
})) {

    /**
     * Explicit ignored-state overrides set the moment the user taps the block button. They make the
     * row redraw instantly instead of waiting for the (async, network-backed) list rebuild, and are
     * cleared once a freshly built list is submitted.
     */
    private val pendingIgnored = mutableMapOf<String, Boolean>()

    private fun ignoredKey(pluginWrapper: PluginWrapper): String {
        return pluginWrapper.plugin.url + "|" + pluginWrapper.plugin.internalName
    }

    /**
     * Applies an instant visual update after the ignored state of [pluginWrapper] changed, so the
     * row does not wait for the list to be rebuilt.
     */
    fun setIgnored(pluginWrapper: PluginWrapper, ignored: Boolean) {
        val key = ignoredKey(pluginWrapper)
        pendingIgnored[key] = ignored
        for (i in 0 until itemCount) {
            val item = getItemOrNull(i) ?: continue
            if (ignoredKey(item.pluginWrapper) == key) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val layout = if (isLayout(TV)) R.layout.repository_item_tv else R.layout.repository_item
        val inflated = LayoutInflater.from(parent.context).inflate(layout, parent, false)

        return RepositoryViewHolderState(
            RepositoryItemBinding.bind(inflated) // may crash
        )
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        if (holder is RepositoryViewHolderState) {
            holder.recycleCount += 1
        }
        when (val binding = holder.view) {
            is RepositoryItemBinding -> {
                clearImage(binding.entryIcon)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindContent(holder: ViewHolderState<Any>, item: PluginViewData, position: Int) {
        val binding = holder.view as? RepositoryItemBinding ?: return
        val itemView = holder.itemView

        val metadata = item.pluginWrapper.plugin
        val disabled = metadata.status == PROVIDER_STATUS_DOWN
        val name = metadata.name.removeSuffix("Provider")
        val isLocal = !item.pluginWrapper.plugin.url.startsWith("http")

        binding.repositoryNameText.isVisible = showRepositoryNames
        if (showRepositoryNames) {
            val name = item.pluginWrapper.repositoryData.name
            binding.repositoryNameText.text = name
        } else {
            binding.repositoryNameText.text = ""
        }

        val drawableInt = if (item.isDownloaded)
            R.drawable.ic_baseline_delete_outline_24
        else R.drawable.netflix_download

        binding.nsfwMarker.isVisible = metadata.tvTypes?.contains(TvType.NSFW.name) ?: false
        binding.actionButton.setImageResource(drawableInt)

        binding.actionButton.setOnClickListener {
            iconClickCallback.invoke(item.pluginWrapper)
        }

        // Ignore / block (skip list) toggle. Only shown on the per-repository plugins list; the
        // top-level extensions list hides it. Blocking a plugin also uninstalls it, and while
        // blocked it is skipped by auto-download, auto-update and batch install/uninstall.
        binding.actionIgnore.isVisible = showIgnoreButton
        val pendingKey = ignoredKey(item.pluginWrapper)
        val realIgnored = PluginManager.isPluginIgnored(
            metadata.internalName,
            metadata.url
        )
        // The instant override is only needed until the persisted state catches up; drop it then.
        if (pendingIgnored[pendingKey] == realIgnored) {
            pendingIgnored.remove(pendingKey)
        }
        val ignored = pendingIgnored[pendingKey] ?: realIgnored
        applyIgnoredStyle(binding, itemView, ignored && showIgnoreButton, disabled)
        if (showIgnoreButton) {
            // Always fully visible, the blocked state is shown by the dimmed row instead.
            binding.actionIgnore.alpha = 1f
            binding.actionIgnore.setImageResource(R.drawable.ic_baseline_block_24)
            // Tint the icon red while the plugin is blocked, so the state is obvious.
            binding.actionIgnore.imageTintList = if (ignored) {
                ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.context, R.color.ignored_plugin_icon)
                )
            } else {
                null
            }
            binding.actionIgnore.setOnClickListener {
                ignoreClickCallback.invoke(item.pluginWrapper)
            }
        }

        fun openDetails() {
            if (isLocal) return
            val sheet = PluginDetailsFragment(item)
            val activity = itemView.context.getActivity() as AppCompatActivity
            sheet.show(activity.supportFragmentManager, "PluginDetails")
        }

        itemView.setOnClickListener {
            openDetails()
        }

        if (item.isDownloaded) {
            // On local plugins page the filepath is provided instead of url.
            val plugin =
                (PluginManager.urlPlugins[metadata.url]
                    ?: (PluginManager.plugins[metadata.url])) as? com.lagradost.cloudstream3.plugins.Plugin

            if (plugin?.openSettings != null) {
                binding.actionSettings.isVisible = true
                binding.actionSettings.setOnClickListener {
                    try {
                        plugin.openSettings?.invoke(itemView.context)
                    } catch (e: Throwable) {
                        Log.e(
                            "PluginAdapter",
                            "Failed to open $name settings: ${
                                Log.getStackTraceString(e)
                            }"
                        )
                    }
                }
            } else {
                binding.actionSettings.isVisible = false
            }
        } else {
            binding.actionSettings.isVisible = false
        }

        val url = metadata.iconUrl?.replace(
            "%size%",
            "$iconSize"
        )?.replace(
            "%exact_size%",
            "$iconSizeExact"
        )

        if (url.isNullOrBlank()) {
            binding.entryIcon.loadImage(R.drawable.ic_baseline_extension_24)
        } else {
            binding.entryIcon.loadImage(
                url
            ) { error(getImageFromDrawable(itemView.context, R.drawable.ic_baseline_extension_24)) }
        }

        binding.extVersion.isVisible = true
        binding.extVersion.text = "v${metadata.version}"

        if (metadata.language.isNullOrBlank()) {
            binding.langIcon.isVisible = false
        } else {
            binding.langIcon.isVisible = true
            binding.langIcon.text = getNameNextToFlagEmoji(metadata.language) ?: metadata.language
        }

        //val oldRecycleCount = (holder as? RepositoryViewHolderState)?.recycleCount

        binding.extVotes.isVisible = false

        // Disable this for now as the vote api is down, this will also significantly improve the lag
        // from doing all these network requests
        /*if (!isLocal) {
            ioSafe {
                metadata.getVotes().main { votes ->
                    val currentRecycleCount = (holder as? RepositoryViewHolderState)?.recycleCount

                    // Only set the text if the view is correctly rendered
                    if (currentRecycleCount == oldRecycleCount) {
                        binding.extVotes.setText(txt(R.string.extension_rating, prettyCount(votes)))
                        binding.extVotes.isVisible = true
                    }
                }
            }
        }*/

        if (metadata.fileSize != null) {
            binding.extFilesize.isVisible = true
            binding.extFilesize.text = formatShortFileSize(itemView.context, metadata.fileSize)
        } else {
            binding.extFilesize.isVisible = false
        }

        binding.mainText.setText(
            if (disabled) txt(
                R.string.single_plugin_disabled,
                name
            ) else txt(name)
        )

        if (isLayout(TV)) {
            // The plugin icon on the left has no action of its own, it only swallows the click
            // so it does not open the plugin details.
            binding.entryIcon.setOnClickListener {
                // no-op
            }

            // Build the focus chain from the icons that are actually visible, so the D-pad
            // never tries to jump to a hidden icon. The plugin icon on the left is not
            // clickable, only the action icons on the right are.
            val actions = listOfNotNull(
                binding.actionIgnore.takeIf { showIgnoreButton && it.isVisible },
                binding.actionSettings.takeIf { it.isVisible },
                binding.actionButton.takeIf { it.isVisible },
            )
            actions.forEachIndexed { index, view ->
                view.nextFocusLeftId =
                    if (index == 0) itemView.id else actions[index - 1].id
                // The last icon keeps its own nextFocusRight (eg. the add repo button).
                if (index < actions.lastIndex) {
                    view.nextFocusRightId = actions[index + 1].id
                }
            }
            itemView.nextFocusRightId = actions.firstOrNull()?.id ?: View.NO_ID

            applyTvRowFocus(
                itemView,
                binding.actionIgnore,
                binding.actionSettings,
                binding.actionButton,
            )
        }

        binding.subText.isGone = metadata.description.isNullOrBlank()
        binding.subText.text = metadata.description.html()
    }

    /**
     * Applies the "blocked" look: a dark overlay plus dimmed text/icon. Works for both the phone
     * layout (root foreground) and the TV layout (card background), so it survives recycling.
     */
    private fun applyIgnoredStyle(
        binding: RepositoryItemBinding,
        itemView: View,
        ignored: Boolean,
        disabled: Boolean,
    ) {
        // The TV layout keeps a focus outline in the card foreground, so the card background is
        // tinted instead. The phone layout uses the root foreground.
        val card = itemView.findViewById<androidx.cardview.widget.CardView>(R.id.background_card)
        if (card != null) {
            card.setCardBackgroundColor(cardBackgroundColor(itemView, ignored))
        } else if (ignored) {
            itemView.foreground = ColorDrawable(fadedOverlayColor(itemView))
        } else {
            // Reset to transparent so the ripple still shows on the phone layout.
            itemView.foreground = ColorDrawable(Color.TRANSPARENT)
        }

        // Dim the text while blocked, otherwise restore it (keeping the disabled-row dimming).
        val textAlpha = when {
            ignored -> 0.35f
            disabled -> 0.6f
            else -> 1f
        }
        binding.mainText.alpha = textAlpha
        binding.subText.alpha = textAlpha
    }

    /**
     * Resolves the themed card background. A blocked plugin gets a faded background, so it is less
     * prominent. It is faded with the theme foreground, so it works on every theme.
     */
    private fun cardBackgroundColor(itemView: View, ignored: Boolean): Int {
        val typed = TypedValue()
        itemView.context.theme.resolveAttribute(R.attr.primaryGrayBackground, typed, true)
        val cardColor = typed.data
        if (!ignored) return cardColor
        return ColorUtils.blendARGB(cardColor, fadedColor(itemView), 0.08f)
    }

    /** The theme foreground, which fades a blocked row on every theme. */
    private fun fadedColor(itemView: View): Int =
        MaterialColors.getColor(itemView, R.attr.textColor)

    /** A faint wash of the theme foreground, for the layouts without a card. */
    private fun fadedOverlayColor(itemView: View): Int =
        ColorUtils.setAlphaComponent(fadedColor(itemView), 0x1A)

    companion object {
        // A high count as we can render in the entire list as the same time
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 15) }

        private tailrec fun findClosestBase2(target: Int, current: Int = 16, max: Int = 512): Int {
            if (current >= max) return max
            if (current >= target) return current
            return findClosestBase2(target, current * 2, max)
        }

        // DO NOT MOVE, as running this test will result in ExceptionInInitializerError on prerelease due to static variables using Resources.getSystem()
        // this test function is only to show how the function works
        /*@Test
        fun testFindClosestBase2() {
            Assert.assertEquals(16, findClosestBase2(0))
            Assert.assertEquals(256, findClosestBase2(170))
            Assert.assertEquals(256, findClosestBase2(256))
            Assert.assertEquals(512, findClosestBase2(257))
            Assert.assertEquals(512, findClosestBase2(700))
        }*/

        private val iconSizeExact = 32.toPx
        private val iconSize by lazy {
            findClosestBase2(iconSizeExact, 16, 512)
        }

        fun prettyCount(number: Number): String? {
            val suffix = charArrayOf(' ', 'k', 'M', 'B', 'T', 'P', 'E')
            val numValue = number.toLong()
            val value = floor(log10(numValue.toDouble())).toInt()
            val base = value / 3
            return if (value >= 3 && base < suffix.size) {
                DecimalFormat("#0.00").format(
                    numValue / 10.0.pow((base * 3).toDouble())
                ) + suffix[base]
            } else {
                DecimalFormat().format(numValue)
            }
        }
    }
}