package co.rivium.push.sdk.inapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import coil.load
import co.rivium.push.sdk.RiviumPushLogger
import kotlin.math.abs

/**
 * View for displaying in-app messages
 */
class InAppMessageView private constructor(
    private val activity: Activity,
    private val message: InAppMessage,
    private val content: InAppMessageContent,
    private val onButtonClick: (InAppButton) -> Unit,
    private val onDismiss: () -> Unit
) {
    private var rootView: FrameLayout? = null
    private var containerView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "RiviumPush.InAppView"
        private const val ANIMATION_DURATION = 300L

        /**
         * Show an in-app message
         */
        fun show(
            activity: Activity,
            message: InAppMessage,
            content: InAppMessageContent,
            onButtonClick: (InAppButton) -> Unit,
            onDismiss: () -> Unit
        ): InAppMessageView {
            return InAppMessageView(activity, message, content, onButtonClick, onDismiss).also {
                it.show()
            }
        }
    }

    private fun show() {
        when (message.type) {
            InAppMessageType.MODAL -> showModal()
            InAppMessageType.BANNER -> showBanner()
            InAppMessageType.FULLSCREEN -> showFullscreen()
            InAppMessageType.CARD -> showCard()
        }
    }

    /**
     * Dismiss the message
     */
    fun dismiss() {
        animateOut {
            rootView?.let { root ->
                (activity.window.decorView as? ViewGroup)?.removeView(root)
            }
            rootView = null
            containerView = null
            onDismiss()
        }
    }

    // ==================== Modal ====================

    private fun showModal() {
        val root = createRootOverlay(dimBackground = true)
        rootView = root

        // Calculate card width - 85% of screen width, no max constraint
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.85f).toInt()

        RiviumPushLogger.d(TAG, "Modal: screenWidth=$screenWidth, cardWidth=$cardWidth")

        val card = CardView(activity).apply {
            radius = dpToPx(16).toFloat()
            cardElevation = dpToPx(8).toFloat()
            setCardBackgroundColor(parseColor(content.backgroundColor, Color.WHITE))
            isClickable = true
        }
        containerView = card

        val cardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Add small internal padding to the card edges
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }

        // Close button row (aligned to end)
        val closeRow = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        closeRow.addView(createCloseButton().apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            }
            layoutParams = params
        })
        cardLayout.addView(closeRow)

        // Image (if present) - constrained height
        content.imageUrl?.let { url ->
            cardLayout.addView(createImage(url, dpToPx(160)))
        }

        // Content
        cardLayout.addView(createContentLayout())

        // Buttons
        if (content.buttons.isNotEmpty()) {
            cardLayout.addView(createButtonLayout())
        }

        card.addView(cardLayout)

        // Center the card with explicit width
        val cardParams = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(card, cardParams)

        // Add to window
        addToWindow(root)
        animateIn(card, AnimationType.SCALE)
    }

    // ==================== Banner ====================

    private fun showBanner() {
        val root = createRootOverlay(dimBackground = false, clickThrough = true)
        rootView = root

        val banner = createCard(
            widthPercent = 0.95f,
            maxWidth = dpToPx(500),
            cornerRadius = dpToPx(12).toFloat()
        )
        containerView = banner

        val bannerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(12), dpToPx(8), dpToPx(12))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Icon/Image (small)
        content.imageUrl?.let { url ->
            val image = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    marginEnd = dpToPx(12)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            image.load(url) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        image.setBackgroundColor(Color.TRANSPARENT)
                    },
                    onError = { _, result ->
                        RiviumPushLogger.e(TAG, "Failed to load banner image: $url, error: ${result.throwable.message}")
                    }
                )
            }
            bannerLayout.addView(image)
        }

        // Text content
        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textLayout.addView(TextView(activity).apply {
            text = content.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(parseColor(content.textColor, Color.BLACK))
            maxLines = 1
        })

        textLayout.addView(TextView(activity).apply {
            text = content.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(parseColor(content.textColor, Color.DKGRAY))
            maxLines = 2
        })

        bannerLayout.addView(textLayout)

        // Action button (first button if present)
        content.buttons.firstOrNull()?.let { button ->
            bannerLayout.addView(createSmallButton(button))
        }

        // Close button
        bannerLayout.addView(createCloseButton(small = true))

        banner.addView(bannerLayout)

        // Position at top
        val bannerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(48)
            leftMargin = dpToPx(8)
            rightMargin = dpToPx(8)
        }
        root.addView(banner, bannerParams)

        // Enable swipe to dismiss
        setupSwipeToDismiss(banner)

        addToWindow(root)
        animateIn(banner, AnimationType.SLIDE_DOWN)
    }

    // ==================== Fullscreen ====================

    private fun showFullscreen() {
        val root = createRootOverlay(dimBackground = false)
        rootView = root

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(parseColor(content.backgroundColor, Color.WHITE))
        }
        containerView = container

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(48), dpToPx(24), dpToPx(24))
        }

        // Close button (top-right)
        container.addView(createCloseButton().apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(16)
                rightMargin = dpToPx(16)
            }
            layoutParams = params
        })

        // Image
        content.imageUrl?.let { url ->
            contentLayout.addView(createImage(url, dpToPx(300)).apply {
                val params = layoutParams as LinearLayout.LayoutParams
                params.bottomMargin = dpToPx(24)
                layoutParams = params
            })
        }

        // Title
        contentLayout.addView(TextView(activity).apply {
            text = content.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(parseColor(content.textColor, Color.BLACK))
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
            layoutParams = params
        })

        // Body
        contentLayout.addView(TextView(activity).apply {
            text = content.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(parseColor(content.textColor, Color.DKGRAY))
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(32)
            }
            layoutParams = params
        })

        // Buttons
        if (content.buttons.isNotEmpty()) {
            contentLayout.addView(createButtonLayout())
        }

        container.addView(contentLayout)
        root.addView(container)

        addToWindow(root)
        animateIn(container, AnimationType.FADE)
    }

    // ==================== Card ====================

    private fun showCard() {
        val root = createRootOverlay(dimBackground = true)
        rootView = root

        val card = createCard(
            widthPercent = 0.9f,
            maxWidth = dpToPx(360),
            cornerRadius = dpToPx(20).toFloat()
        )
        containerView = card

        val cardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Image (hero style at top)
        content.imageUrl?.let { url ->
            cardLayout.addView(createImage(url, dpToPx(180)))
        }

        // Content with padding
        val contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
        }

        // Title
        contentContainer.addView(TextView(activity).apply {
            text = content.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(parseColor(content.textColor, Color.BLACK))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = params
        })

        // Body
        contentContainer.addView(TextView(activity).apply {
            text = content.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(parseColor(content.textColor, Color.DKGRAY))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(20)
            }
            layoutParams = params
        })

        // Buttons
        if (content.buttons.isNotEmpty()) {
            contentContainer.addView(createButtonLayout())
        }

        cardLayout.addView(contentContainer)

        // Close button overlay
        val closeOverlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        closeOverlay.addView(createCloseButton().apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(8)
                rightMargin = dpToPx(8)
            }
            layoutParams = params
        })

        val cardContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        cardContainer.addView(cardLayout)
        cardContainer.addView(closeOverlay)

        card.addView(cardContainer)

        // Center the card
        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(card, cardParams)

        addToWindow(root)
        animateIn(card, AnimationType.SLIDE_UP)
    }

    // ==================== Helper Methods ====================

    private fun createRootOverlay(dimBackground: Boolean, clickThrough: Boolean = false): FrameLayout {
        return FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            if (dimBackground) {
                setBackgroundColor(Color.parseColor("#80000000"))
                setOnClickListener {
                    dismiss()
                }
            } else if (!clickThrough) {
                isClickable = true
            }
        }
    }

    private fun createCard(widthPercent: Float, maxWidth: Int, cornerRadius: Float): CardView {
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val calculatedWidth = (screenWidth * widthPercent).toInt()
        val cardWidth = if (maxWidth > 0) minOf(calculatedWidth, maxWidth) else calculatedWidth

        RiviumPushLogger.d(TAG, "Creating card: screenWidth=$screenWidth, widthPercent=$widthPercent, maxWidth=$maxWidth, cardWidth=$cardWidth")

        return CardView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            radius = cornerRadius
            cardElevation = dpToPx(8).toFloat()
            setCardBackgroundColor(parseColor(content.backgroundColor, Color.WHITE))
            isClickable = true // Prevent clicks from passing through
        }
    }

    private fun createCloseButton(small: Boolean = false): View {
        val size = if (small) dpToPx(32) else dpToPx(40)
        return ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.GRAY)
            background = null
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener { dismiss() }
        }
    }

    private fun createImage(url: String, height: Int): ImageView {
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#F5F5F5")) // Light gray placeholder

            RiviumPushLogger.d(TAG, "Loading image from URL: $url")

            load(url) {
                crossfade(true)
                listener(
                    onStart = {
                        RiviumPushLogger.d(TAG, "Image loading started: $url")
                    },
                    onSuccess = { _, _ ->
                        RiviumPushLogger.d(TAG, "Image loaded successfully: $url")
                        setBackgroundColor(Color.TRANSPARENT)
                    },
                    onError = { _, result ->
                        RiviumPushLogger.e(TAG, "Failed to load image: $url, error: ${result.throwable.message}")
                        // Keep the gray background as fallback
                    }
                )
            }
        }
    }

    private fun createContentLayout(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val horizontalPadding = dpToPx(20)
            val topPadding = dpToPx(12)
            val bottomPadding = dpToPx(16)
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)

            // Title
            addView(TextView(activity).apply {
                text = content.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(parseColor(content.textColor, Color.BLACK))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                }
                layoutParams = params
            })

            // Body
            addView(TextView(activity).apply {
                text = content.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(parseColor(content.textColor, Color.DKGRAY))
                setLineSpacing(0f, 1.3f)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = params
            })
        }
    }

    private fun createButtonLayout(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), dpToPx(20))

            content.buttons.forEachIndexed { index, button ->
                if (index > 0) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(12), 0)
                    })
                }
                addView(createButton(button))
            }
        }
    }

    private fun createButton(button: InAppButton): TextView {
        return TextView(activity).apply {
            text = button.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))

            val bgColor = when (button.style) {
                InAppButtonStyle.PRIMARY -> Color.parseColor("#2196F3")
                InAppButtonStyle.SECONDARY -> Color.parseColor("#E0E0E0")
                InAppButtonStyle.DESTRUCTIVE -> Color.parseColor("#F44336")
                InAppButtonStyle.TEXT -> Color.TRANSPARENT
            }

            val textColor = when (button.style) {
                InAppButtonStyle.PRIMARY, InAppButtonStyle.DESTRUCTIVE -> Color.WHITE
                InAppButtonStyle.SECONDARY -> Color.BLACK
                InAppButtonStyle.TEXT -> Color.parseColor("#2196F3")
            }

            setTextColor(textColor)

            if (button.style != InAppButtonStyle.TEXT) {
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dpToPx(8).toFloat()
                }
            }

            setOnClickListener {
                onButtonClick(button)
            }
        }
    }

    private fun createSmallButton(button: InAppButton): TextView {
        return TextView(activity).apply {
            text = button.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setTextColor(Color.parseColor("#2196F3"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = params

            setOnClickListener {
                onButtonClick(button)
            }
        }
    }

    private fun setupSwipeToDismiss(view: View) {
        var initialY = 0f
        var initialTranslationY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    initialTranslationY = v.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - initialY
                    if (deltaY < 0) { // Only allow swipe up
                        v.translationY = initialTranslationY + deltaY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val deltaY = event.rawY - initialY
                    if (abs(deltaY) > dpToPx(100)) {
                        dismiss()
                    } else {
                        ObjectAnimator.ofFloat(v, "translationY", v.translationY, 0f).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun addToWindow(view: View) {
        (activity.window.decorView as? ViewGroup)?.addView(view)
    }

    private enum class AnimationType {
        FADE, SCALE, SLIDE_UP, SLIDE_DOWN
    }

    private fun animateIn(view: View, type: AnimationType) {
        when (type) {
            AnimationType.FADE -> {
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            AnimationType.SCALE -> {
                view.scaleX = 0.8f
                view.scaleY = 0.8f
                view.alpha = 0f
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            AnimationType.SLIDE_UP -> {
                view.translationY = dpToPx(100).toFloat()
                view.alpha = 0f
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            AnimationType.SLIDE_DOWN -> {
                view.translationY = -dpToPx(100).toFloat()
                view.alpha = 0f
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun animateOut(onComplete: () -> Unit) {
        val view = containerView ?: run {
            onComplete()
            return
        }

        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            .start()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    private fun parseColor(colorString: String?, default: Int): Int {
        return try {
            if (colorString != null) Color.parseColor(colorString) else default
        } catch (e: Exception) {
            default
        }
    }
}
