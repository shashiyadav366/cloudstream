package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * Central filter for promotional popups.
 *
 * Extension dialogs that use the Application context are caught by wrapping its WindowManager.
 * Extension popups that use a DialogFragment are caught through the Fragment lifecycle.
 *
 * A promotional window is never removed from WindowManager. It is made invisible, so the
 * extension can still dismiss it normally.
 */
object PromoPopupFilter {
    private const val TAG = "PromoPopupFilter"
    private const val MAX_TEXT_NODES = 250
    private const val MAX_TEXT_LENGTH = 1_000
    private const val MAX_WEBVIEW_CHECKS = 12

    private val installedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val activityDecors = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * WebView-based popups keep all their text inside the page, so the native view tree only
     * exposes the page title. Track each WebView while its body text is read asynchronously.
     */
    private val checkedWebViews = WeakHashMap<WebView, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var resumedActivities = 0
    private var scanning = false
    private var rootViewsField: Field? = null
    private var rootViewsInstance: Any? = null
    private var scanFailureLogged = false

    private val scanRunnable = object : Runnable {
        override fun run() {
            scanRootWindows()
            if (scanning) handler.postDelayed(this, 250)
        }
    }

    fun onActivityResumed(activity: Activity) {
        activityDecors.add(activity.window.decorView)
        resumedActivities++
        if (!scanning) {
            scanning = true
            handler.post(scanRunnable)
        }
    }

    fun onActivityPaused() {
        if (resumedActivities > 0) resumedActivities--
        if (resumedActivities == 0) {
            scanning = false
            handler.removeCallbacks(scanRunnable)
        }
    }

    /**
     * Check every window that is not an Activity's main window. This catches plain Dialog
     * windows, including dialogs whose content is a WebView with HTML text.
     */
    private fun scanRootWindows() {
        val roots = try {
            getWindowRoots()
        } catch (t: Throwable) {
            if (!scanFailureLogged) {
                scanFailureLogged = true
                Log.w(TAG, "Could not scan popup windows", t)
            }
            return
        }

        for (root in roots) {
            if (root in activityDecors) continue
            if (root.visibility == View.GONE) continue
            val params = root.layoutParams as? WindowManager.LayoutParams ?: continue
            if (params.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) continue

            val decision = classify(root, params)
            if (decision.isPromotional) {
                Log.i(TAG, "Suppressed popup window: ${decision.reasons.joinToString()}")
                hideWindow(root, params)
            } else {
                inspectWebViews(root, params)
            }
        }
    }

    /**
     * Text in a WebView is not part of the native view tree. Extensions that build their
     * promo as HTML (for example a Telegram join dialog) therefore look like an empty window
     * to [collectTexts]. Read the rendered page text and classify it asynchronously.
     */
    private fun inspectWebViews(root: View, params: WindowManager.LayoutParams) {
        val webViews = ArrayList<WebView>(2)
        fun visit(view: View, depth: Int) {
            if (depth > 30) return
            if (view is WebView) {
                webViews.add(view)
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                }
            }
        }
        visit(root, 0)

        for (webView in webViews) {
            if (checkedWebViews.containsKey(webView)) continue
            checkedWebViews[webView] = 0
            inspectWebView(root, webView, params)
        }
    }

    private fun inspectWebView(
        root: View,
        webView: WebView,
        params: WindowManager.LayoutParams
    ) {
        val attempts = checkedWebViews[webView] ?: return
        if (attempts >= MAX_WEBVIEW_CHECKS) return
        if (!webView.isAttachedToWindow) {
            checkedWebViews.remove(webView)
            return
        }
        checkedWebViews[webView] = attempts + 1

        try {
            webView.evaluateJavascript(
                "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()"
            ) { pageText ->
                // Do not touch a dialog that was already dismissed while the script ran.
                if (!webView.isAttachedToWindow || root.visibility == View.GONE) {
                    checkedWebViews.remove(webView)
                    return@evaluateJavascript
                }

                val decision = PromoPopupClassifier.classify(
                    listOfNotNull(pageText, webView.title, params.title?.toString())
                )
                if (decision.isPromotional) {
                    checkedWebViews[webView] = MAX_WEBVIEW_CHECKS
                    Log.i(
                        TAG,
                        "Suppressed WebView popup: ${decision.reasons.joinToString()}"
                    )
                    closeWebViewPopup(root, webView, params)
                } else {
                    webView.postDelayed(
                        { inspectWebView(root, webView, params) },
                        300L
                    )
                }
            }
        } catch (t: Throwable) {
            checkedWebViews[webView] = MAX_WEBVIEW_CHECKS
            Log.d(TAG, "Could not read popup WebView text", t)
        }
    }

    /**
     * Many extension popups expose a tiny JavaScript bridge whose close() method dismisses
     * the dialog properly. Prefer that over only hiding the window: it removes the dim layer
     * and runs the extension's own cleanup. If the bridge is missing or does nothing, fall
     * back to detaching the window.
     */
    private fun closeWebViewPopup(
        root: View,
        webView: WebView,
        params: WindowManager.LayoutParams
    ) {
        val script = "(function(){try{" +
                "if(window.AndroidPopup&&typeof window.AndroidPopup.close==='function'){" +
                "window.AndroidPopup.close();return 'closed'}" +
                "return 'no-bridge'}catch(e){return 'error'}})()"
        try {
            webView.evaluateJavascript(script) { result ->
                if (result?.trim('"') != "closed") {
                    hideWindow(root, params)
                }
            }
        } catch (t: Throwable) {
            hideWindow(root, params)
        }

        // The bridge usually dismisses on the next main-loop pass. If the window is still
        // there after that, remove it ourselves.
        handler.postDelayed({
            if (root.isAttachedToWindow) {
                hideWindow(root, params)
            }
        }, 400L)
    }

    private fun getWindowRoots(): List<View> {
        var field = rootViewsField
        if (field == null) {
            val clazz = Class.forName("android.view.WindowManagerGlobal")
            val instance = clazz.getMethod("getInstance").invoke(null)
            field = clazz.getDeclaredField("mViews").apply { isAccessible = true }
            rootViewsField = field
            rootViewsInstance = instance
        }
        val views = field.get(rootViewsInstance) as? List<*> ?: return emptyList()
        return views.filterIsInstance<View>()
    }

    private fun hideWindow(root: View, params: WindowManager.LayoutParams) {
        try {
            root.visibility = View.GONE
            root.alpha = 0f
            val hidden = neutralizedParams(params)
            if (hidden != null) {
                val windowManager =
                    root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager?.updateViewLayout(root, hidden)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Could not hide popup window", t)
        }

        // An invisible dialog can still own the input focus / touchable window on some
        // devices, leaving the whole app unresponsive. Detach the promo window on the next
        // main-loop pass so we do not tear down a view from inside its own JS callback.
        handler.post { detachWindow(root) }
    }

    /**
     * Remove a promotional window. The public WindowManager path is used first; the
     * WindowManagerGlobal reflection is only a fallback for contexts that do not expose a
     * WindowManager service.
     */
    private fun detachWindow(root: View) {
        try {
            if (!root.isAttachedToWindow) return
            val windowManager =
                root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                windowManager.removeViewImmediate(root)
                return
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Could not detach popup window through WindowManager", t)
        }

        try {
            if (!root.isAttachedToWindow) return
            val clazz = Class.forName("android.view.WindowManagerGlobal")
            val instance = clazz.getMethod("getInstance").invoke(null)
            val removeView = clazz.getMethod(
                "removeView",
                View::class.java,
                Boolean::class.javaPrimitiveType!!
            )
            removeView.invoke(instance, root, true)
        } catch (t: Throwable) {
            Log.d(TAG, "Could not detach popup window through WindowManagerGlobal", t)
        }
    }

    /**
     * Register a fragment lifecycle callback for this Activity. This catches full-screen
     * DialogFragment popups such as the donation/community dialogs used by some extensions.
     */
    fun installForActivity(activity: Activity) {
        // Never classify/detach an Activity's own main window.
        activityDecors.add(activity.window.decorView)
        if (activity !is FragmentActivity || !installedActivities.add(activity)) return
        try {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        v: View,
                        savedInstanceState: Bundle?
                    ) {
                        checkDialog(f, v)
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        f.view?.let { checkDialog(f, it) }
                    }
                },
                true
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not install fragment popup filter", t)
        }
    }

    private fun checkDialog(fragment: Fragment, view: View) {
        if (fragment !is DialogFragment) return
        val decision = PromoPopupClassifier.classify(collectTexts(view))
        if (!decision.isPromotional) return

        Log.i(TAG, "Suppressed DialogFragment: ${decision.reasons.joinToString()}")
        // Hide immediately, then dismiss after the dialog is attached so the window cannot
        // intercept touches or dim the screen.
        view.visibility = View.GONE
        view.post { fragment.dismissAllowingStateLoss() }
    }

    fun wrap(base: WindowManager): WindowManager {
        return try {
            Proxy.newProxyInstance(
                PromoPopupFilter::class.java.classLoader,
                arrayOf(WindowManager::class.java),
                FilteringWindowManager(base)
            ) as WindowManager
        } catch (t: Throwable) {
            Log.w(TAG, "Could not install popup filter", t)
            base
        }
    }

    internal fun collectTexts(root: View): List<String> {
        val texts = ArrayList<String>(16)
        var visited = 0

        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim()
            if (!text.isNullOrEmpty() && texts.size < MAX_TEXT_NODES) {
                texts.add(text.take(MAX_TEXT_LENGTH))
            }
        }

        fun visit(view: View, depth: Int) {
            if (visited++ >= MAX_TEXT_NODES || depth > 30) return
            if (view.visibility == View.GONE) return

            add(view.contentDescription)
            when (view) {
                is TextView -> {
                    add(view.text)
                    add(view.hint)
                    add(view.error)
                }
                is WebView -> {
                    // The rendered page text is not available synchronously. The page title and
                    // content description are still useful hints for native JS dialogs.
                    add(view.title)
                }
            }
            (view.tag as? CharSequence)?.let { add(it) }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                }
            }
        }

        visit(root, 0)
        return texts
    }

    private fun classify(
        view: View,
        params: ViewGroup.LayoutParams?
    ): PromoPopupClassifier.Decision {
        val texts = collectTexts(view).toMutableList()
        (params as? WindowManager.LayoutParams)?.title?.let { texts.add(it.toString()) }
        return PromoPopupClassifier.classify(texts)
    }

    private fun neutralizedParams(
        params: ViewGroup.LayoutParams?
    ): WindowManager.LayoutParams? {
        val windowParams = params as? WindowManager.LayoutParams ?: return null
        return WindowManager.LayoutParams().apply {
            copyFrom(windowParams)
            width = 1
            height = 1
            alpha = 0f
            dimAmount = 0f
            flags = (flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()) or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun neutralize(base: WindowManager, view: View, params: ViewGroup.LayoutParams?) {
        try {
            view.alpha = 0f
            view.setBackgroundColor(Color.TRANSPARENT)
            view.visibility = View.GONE
            val hiddenParams = neutralizedParams(params) ?: return
            base.updateViewLayout(view, hiddenParams)
        } catch (t: Throwable) {
            // The window may already have been removed by the extension.
            Log.d(TAG, "Could not hide promotional window", t)
        }
    }

    private fun hideWhenAttached(
        base: WindowManager,
        view: View,
        params: ViewGroup.LayoutParams?
    ) {
        val hide = Runnable { neutralize(base, view, params) }
        if (view.isAttachedToWindow) {
            view.post(hide)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    v.post(hide)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }
    }

    /** Some custom dialogs fill their text after [WindowManager.addView], so re-check briefly. */
    private fun recheckLater(
        base: WindowManager,
        view: View,
        params: ViewGroup.LayoutParams?
    ) {
        var checks = 0
        val check = object : Runnable {
            override fun run() {
                if (checks++ >= 8) return
                if (!view.isAttachedToWindow) {
                    view.postDelayed(this, 200)
                    return
                }
                val decision = classify(view, params ?: view.layoutParams)
                if (decision.isPromotional) {
                    Log.i(
                        TAG,
                        "Suppressed promotional popup (late): ${decision.reasons.joinToString()}"
                    )
                    neutralize(base, view, params ?: view.layoutParams)
                } else {
                    view.postDelayed(this, 300)
                }
            }
        }
        view.postDelayed(check, 100)
    }

    private class FilteringWindowManager(
        private val base: WindowManager
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "addView" && args != null && args.isNotEmpty()) {
                return handleAddView(method, args)
            }
            return invokeBase(method, args)
        }

        private fun handleAddView(method: Method, args: Array<out Any?>): Any? {
            val view = args.getOrNull(0) as? View ?: return invokeBase(method, args)
            val originalParams = args.getOrNull(1) as? ViewGroup.LayoutParams
            val decision = classify(view, originalParams)

            if (decision.isPromotional) {
                Log.i(
                    TAG,
                    "Suppressed promotional popup: ${decision.reasons.joinToString()}"
                )
                // Make the window invisible from its very first frame (no flash), while still
                // actually attaching it so Dialog.dismiss() keeps working later.
                view.alpha = 0f
                val hiddenParams = neutralizedParams(originalParams)
                val result = if (hiddenParams != null && args.size >= 2) {
                    val filteredArgs = arrayOfNulls<Any?>(args.size)
                    for (index in args.indices) {
                        filteredArgs[index] = args[index]
                    }
                    filteredArgs[1] = hiddenParams
                    invokeBase(method, filteredArgs)
                } else {
                    invokeBase(method, args)
                }
                hideWhenAttached(base, view, hiddenParams ?: originalParams)
                return result
            }

            val result = invokeBase(method, args)
            recheckLater(base, view, originalParams)
            return result
        }

        private fun invokeBase(method: Method, args: Array<out Any?>?): Any? {
            return try {
                if (args.isNullOrEmpty()) {
                    method.invoke(base)
                } else {
                    method.invoke(base, *args)
                }
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        }
    }
}
