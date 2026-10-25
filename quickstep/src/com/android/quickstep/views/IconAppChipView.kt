/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.views

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextUtils.TruncateAt
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.addListener
import androidx.core.view.updateLayoutParams
import com.android.app.animation.Interpolators
import com.android.internal.graphics.drawable.PetalGlassDrawable
import com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.petalos.PetalRecentsPrefs
import com.android.launcher3.util.MultiPropertyFactory
import com.android.launcher3.util.MultiPropertyFactory.FloatBiFunction
import com.android.launcher3.util.MultiValueAlpha
import com.android.quickstep.util.BorderAnimator
import com.android.quickstep.util.BorderAnimator.Companion.createSimpleBorderAnimator
import com.android.quickstep.util.RecentsOrientedState
import kotlin.math.max
import kotlin.math.min

/** An icon app menu view which can be used in place of an IconView in overview TaskViews. */
class IconAppChipView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), TaskViewIcon {

    private var iconView: IconView? = null
    private var menuAnchorView: View? = null

    // Two textview so we can ellipsize the collapsed view and crossfade on expand to the full name.
    private var appTitle: TextView? = null
    private var isLayoutNaturalToLauncher = true

    private val backgroundRelativeLtrLocation = Rect()
    private val backgroundAnimationRectEvaluator = RectEvaluator(backgroundRelativeLtrLocation)
    private val glass = PetalGlassDrawable(this, 24f, 0f, false)
    private val glassSetting = "petal_recents_refraction"
    private val glassObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = updateGlassEffect()
    }
    private val glassBounds = Rect()

    // Menu dimensions
    private val collapsedMenuDefaultWidth: Int =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_collapsed_width)
    private val expandedMenuDefaultWidth: Int =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_expanded_width)
    private val collapsedMenuDefaultHeight =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_collapsed_height)
    private val expandedMenuDefaultHeight =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_expanded_height)
    private val iconMenuMarginTopStart =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_expanded_top_start_margin)
    private val menuToChipGap: Int =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_expanded_gap)

    // Background dimensions
    val backgroundMarginTopStart: Int =
        resources.getDimensionPixelSize(
            R.dimen.task_thumbnail_icon_menu_background_margin_top_start
        )

    // Contents dimensions
    private val appNameHorizontalMarginCollapsed =
        resources.getDimensionPixelSize(
            R.dimen.task_thumbnail_icon_menu_app_name_margin_horizontal_collapsed
        )
    private val appNameHorizontalMarginExpanded =
        resources.getDimensionPixelSize(
            R.dimen.task_thumbnail_icon_menu_app_name_margin_horizontal_expanded
        )
    private val arrowMarginEnd =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_arrow_margin)
    private val iconViewMarginStart =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_view_start_margin)
    private val appIconSize =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_app_icon_collapsed_size)
    private val iconMenuElevation =
        resources.getDimension(R.dimen.task_thumbnail_icon_menu_elevation)
    private val iconViewDrawableExpandedSize =
        resources.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_app_icon_expanded_size)
    private val focusBorderWidth =
        resources.getDimensionPixelSize(R.dimen.app_chip_keyboard_border_width)
    private val cornerRadius = resources.getDimensionPixelSize(R.dimen.app_chip_round_corner_radius)

    private var animator: AnimatorSet? = null

    private val multiValueAlpha: MultiValueAlpha =
        MultiValueAlpha(this, NUM_ALPHA_CHANNELS).apply { setUpdateVisibility(true) }

    private val viewTranslationX: MultiPropertyFactory<View> =
        MultiPropertyFactory(this, VIEW_TRANSLATE_X, INDEX_COUNT_TRANSLATION, SUM_AGGREGATOR)

    private val viewTranslationY: MultiPropertyFactory<View> =
        MultiPropertyFactory(this, VIEW_TRANSLATE_Y, INDEX_COUNT_TRANSLATION, SUM_AGGREGATOR)

    // don't let the name squash the damn icon
    private val minWidthAllowed = iconViewMarginStart + appIconSize + arrowMarginEnd
    var maxWidth = Integer.MAX_VALUE
        // split screen can squeeze this pretty hard
        set(value) {
            // don't squash the icon too
            field = max(value, minWidthAllowed)
        }

    var status: AppChipStatus = AppChipStatus.Collapsed
        private set

    // grid chips ditch the name
    private var iconOnlyMode: Boolean = false

    var isBelowTask: Boolean = false
        private set

    // enough room for the icon, that's it
    private val iconOnlyChipWidth: Int
        get() = backgroundMarginTopStart + iconViewMarginStart + appIconSize + backgroundMarginTopStart

    val menuToCollapsedChipGap: Int =
        getExpandedBackgroundLtrBounds().bottom -
            getCollapsedBackgroundLtrBounds().bottom -
            menuToChipGap

    private val focusBorderAnimator: BorderAnimator =
        createSimpleBorderAnimator(
            borderRadiusPx = cornerRadius,
            borderWidthPx = focusBorderWidth,
            boundsBuilder = { bounds ->
                bounds.set(backgroundRelativeLtrLocation)
                if (status == AppChipStatus.Expanded) {
                    // Draws the border inside the chip to avoid overlap with the task menu.
                    var inset = focusBorderWidth - 1
                    bounds.inset(inset, inset)
                }
            },
            targetView = this,
            borderColor =
                context
                    .obtainStyledAttributes(attrs, R.styleable.IconAppChip)
                    .getColor(
                        R.styleable.IconAppChip_focusBorderColor,
                        BorderAnimator.DEFAULT_BORDER_COLOR,
                    ),
        )

    private var focusAnimator: AnimatorSet? = null

    private fun animateFocusBorder(isAppearing: Boolean) {
        focusAnimator?.cancel()
        focusAnimator = null
        val borderAnimator = focusBorderAnimator.buildAnimator(isAppearing)

        val initialBackground = Rect(backgroundRelativeLtrLocation)
        val targetBackground: Rect =
            when {
                // Background animator to increase the clipping size to show the focus border.
                isAppearing ->
                    Rect(backgroundRelativeLtrLocation).apply {
                        if (status == AppChipStatus.Collapsed)
                            inset(-focusBorderWidth + 1, -focusBorderWidth + 1)
                    }
                // Background animator to restore the outline size to hide the focus border
                status == AppChipStatus.Expanded -> getExpandedBackgroundLtrBounds()
                else -> getCollapsedBackgroundLtrBounds()
            }
        val backgroundAnimator =
            ValueAnimator.ofObject(
                    backgroundAnimationRectEvaluator,
                    initialBackground,
                    targetBackground,
                )
                .apply { addUpdateListener { invalidateOutline() } }

        focusAnimator =
            AnimatorSet().apply {
                playTogether(borderAnimator, backgroundAnimator)
                duration = borderAnimator.duration
                interpolator = borderAnimator.interpolator
                start()
            }
    }

    public override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocusBorder(isAppearing = gainFocus)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        focusBorderAnimator.drawBorder(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(glassSetting), false, glassObserver)
        updateGlassEffect()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(glassObserver)
        super.onDetachedFromWindow()
    }

    private fun updateGlassEffect() {
        glass.setBackdropEnabled(Settings.Secure.getInt(context.contentResolver, glassSetting, 0) == 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        glassBounds.set(backgroundRelativeLtrLocation)
        if (isLayoutRtl) {
            glassBounds.left = width - backgroundRelativeLtrLocation.right
            glassBounds.right = width - backgroundRelativeLtrLocation.left
        }
        glass.bounds = glassBounds
        glass.setGlassCorners(cornerRadius.toFloat(), cornerRadius.toFloat())
        glass.draw(canvas)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        background = null
        setWillNotDraw(false)
        iconView = findViewById(R.id.icon_view)
        appTitle = findViewById(R.id.icon_title)
        menuAnchorView = findViewById(R.id.icon_view_menu_anchor)
    }

    override fun setText(text: CharSequence?) {
        if (text == appTitle?.text) return
        appTitle?.text = text
    }

    override fun getDrawable(): Drawable? = iconView?.drawable

    private var currentIconDrawableHash: Int = 0

    override fun setDrawable(icon: Drawable?) {
        if (icon.hashCode() == currentIconDrawableHash) return
        iconView?.drawable = icon
        currentIconDrawableHash = icon.hashCode()
    }

    override fun setDrawableSize(iconWidth: Int, iconHeight: Int) {
        iconView?.setDrawableSize(iconWidth, iconHeight)
    }

    override fun getMinimumWidth(): Int = min(maxWidth, collapsedMenuDefaultWidth)

    override fun setIconOrientation(orientationState: RecentsOrientedState, isGridTask: Boolean) {
        val orientationHandler = orientationState.orientationHandler
        isLayoutNaturalToLauncher = orientationHandler.isLayoutNaturalToLauncher
        val taskView = parent as? TaskView
        val container: RecentsViewContainer = RecentsViewContainer.containerFromContext(context)
        val hideGridChip = PetalRecentsPrefs.isGrid()
        multiValueAlpha[INDEX_GRID_ALPHA].value = if (hideGridChip) 0f else 1f
        isBelowTask =
            taskView?.type == TaskViewType.SINGLE &&
                !isGridTask &&
                !hideGridChip &&
                !container.deviceProfile.deviceProperties.isTablet &&
                isLayoutNaturalToLauncher
        appTitle?.apply {
            gravity = if (isBelowTask) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = if (isBelowTask) TEXT_ALIGNMENT_CENTER else TEXT_ALIGNMENT_VIEW_START
            if (isBelowTask) ellipsize = TruncateAt.END
        }
        // grid gets just the icon, less clutter
        if (iconOnlyMode != isGridTask) {
            iconOnlyMode = isGridTask
            appTitle?.visibility = if (isGridTask) View.GONE else View.VISIBLE
        }
        val anchorLayoutParams = menuAnchorView!!.layoutParams as LayoutParams
        if (orientationHandler.isLayoutNaturalToLauncher) {
            anchorLayoutParams.gravity = Gravity.START
            anchorLayoutParams.marginStart =
                if (isBelowTask) getCollapsedBackgroundLtrBounds().left
                else backgroundMarginTopStart
        } else {
            anchorLayoutParams.gravity = Gravity.LEFT
            anchorLayoutParams.marginStart = 0
        }
        anchorLayoutParams.topMargin = expandedMenuDefaultHeight + menuToChipGap
        menuAnchorView!!.layoutParams = anchorLayoutParams

        // Layout Params for the Menu View (this)
        val iconMenuParams = layoutParams as LayoutParams
        iconMenuParams.width = getChipWidth()
        iconMenuParams.height = expandedMenuDefaultHeight
        orientationHandler.setIconAppChipMenuParams(
            this,
            iconMenuParams,
            iconMenuMarginTopStart,
            iconMenuMarginTopStart,
        )
        layoutParams = iconMenuParams

        // Layout params for the background
        val collapsedBackgroundBounds = getCollapsedBackgroundLtrBounds()
        backgroundRelativeLtrLocation.set(collapsedBackgroundBounds)
        outlineProvider =
            object : ViewOutlineProvider() {
                val mRtlAppliedOutlineBounds: Rect = Rect()

                override fun getOutline(view: View, outline: Outline) {
                    mRtlAppliedOutlineBounds.set(backgroundRelativeLtrLocation)
                    if (isLayoutRtl) {
                        val width = width
                        mRtlAppliedOutlineBounds.left = width - backgroundRelativeLtrLocation.right
                        mRtlAppliedOutlineBounds.right = width - backgroundRelativeLtrLocation.left
                    }
                    outline.setRoundRect(
                        mRtlAppliedOutlineBounds,
                        resources.getDimension(R.dimen.app_chip_round_corner_radius),
                    )
                }
            }

        // Layout Params for the Icon View
        val iconParams = iconView!!.layoutParams as LayoutParams
        val iconMarginStartRelativeToParent =
            iconViewMarginStart + if (isBelowTask) 0 else backgroundMarginTopStart
        orientationHandler.setIconAppChipChildrenParams(iconParams, iconMarginStartRelativeToParent)

        iconView!!.layoutParams = iconParams
        iconView!!.setDrawableSize(appIconSize, appIconSize)

        // Layout Params for the collapsed Icon Text View
        val textMarginStart =
            if (isBelowTask) {
                (expandedMenuDefaultWidth -
                    calculateCollapsedTextWidth(collapsedBackgroundBounds.width())) / 2
            } else {
                iconMarginStartRelativeToParent + appIconSize + appNameHorizontalMarginCollapsed
            }
        val iconTextCollapsedParams = appTitle!!.layoutParams as LayoutParams
        orientationHandler.setIconAppChipChildrenParams(iconTextCollapsedParams, textMarginStart)
        iconTextCollapsedParams.width =
            calculateCollapsedTextWidth(collapsedBackgroundBounds.width())
        appTitle?.layoutParams = iconTextCollapsedParams
        if (isBelowTask) updateBelowTaskIconPosition()

        // rotation calls this twice; requestLayout alone misses the new width
        measure(
            MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(layoutParams.height, MeasureSpec.EXACTLY),
        )
    }

    private fun enableMarquee(isEnabled: Boolean) {
        // Marquee should not be enabled when is running test harness.
        val isMarqueeEnabled = isEnabled && !isBelowTask && !Utilities.isRunningInTestHarness()
        appTitle?.let {
            it.ellipsize =
                if (isMarqueeEnabled) TruncateAt.MARQUEE
                else if (isBelowTask) TruncateAt.END
                else null
            it.isSelected = isMarqueeEnabled
        }
    }

    fun layoutBelowTask(taskWidth: Int, taskHeight: Int, gap: Int) {
        // center the actual pill, not its lopsided view bounds
        val bounds = getCollapsedBackgroundLtrBounds()
        val backgroundLeft = if (isLayoutRtl) measuredWidth - bounds.right else bounds.left
        val left = (taskWidth - bounds.width()) / 2 - backgroundLeft
        val top = taskHeight + gap - bounds.top
        layout(left, top, left + measuredWidth, top + measuredHeight)
    }

    private fun updateBelowTaskIconPosition() {
        // one fixed layout, the glass and icon do the growing
        val collapsedWidth = getCollapsedBackgroundLtrBounds().width()
        val expansion =
            ((backgroundRelativeLtrLocation.width() - collapsedWidth).toFloat() /
                (expandedMenuDefaultWidth - collapsedWidth).coerceAtLeast(1)).coerceIn(0f, 1f)
        val scale = 1f + (iconViewDrawableExpandedSize / appIconSize.toFloat() - 1f) * expansion
        iconView?.apply {
            scaleX = scale
            scaleY = scale
            val offset = backgroundRelativeLtrLocation.left + appIconSize * (scale - 1f) / 2f
            translationX = if (isLayoutRtl) -offset else offset
        }
        appTitle?.translationX = 0f
    }

    // name gets whatever room the icon left
    private fun calculateCollapsedTextWidth(width: Int): Int {
        if (isBelowTask) {
            // leave the same room on both sides or the label looks drunk
            val sideSpace = iconViewMarginStart + appIconSize + appNameHorizontalMarginCollapsed
            return (width - 2 * sideSpace).coerceAtLeast(0)
        }
        val collapsedTextWidth =
            (width -
                iconViewMarginStart -
                appIconSize -
                appNameHorizontalMarginCollapsed -
                arrowMarginEnd)

        val spaceLeftForText = maxWidth - minWidthAllowed
        return minOf(collapsedTextWidth, spaceLeftForText).coerceAtLeast(0)
    }

    private fun calculateExpandedTextWidth(width: Int): Int {
        if (isBelowTask) {
            val sideSpace =
                backgroundMarginTopStart + iconViewMarginStart +
                    (appIconSize + iconViewDrawableExpandedSize) / 2 +
                    appNameHorizontalMarginCollapsed
            return (width - 2 * sideSpace).coerceAtLeast(0)
        }
        return width -
            iconViewMarginStart -
            iconViewDrawableExpandedSize -
            appNameHorizontalMarginExpanded -
            arrowMarginEnd
    }

    override fun setIconColorTint(color: Int, amount: Float) {
        // RecentsView's COLOR_TINT animates between 0 and 0.5f, we want to hide the app chip menu.
        val colorTintAlpha = Utilities.mapToRange(amount, 0f, 0.5f, 1f, 0f, Interpolators.LINEAR)
        multiValueAlpha[INDEX_COLOR_FILTER_ALPHA].value = colorTintAlpha
    }

    override fun setContentAlpha(alpha: Float) {
        multiValueAlpha[INDEX_CONTENT_ALPHA].value = alpha
    }

    fun setDismissAlpha(alpha: Float) {
        multiValueAlpha[INDEX_DISMISS_ALPHA].value = alpha
    }

    override fun setModalAlpha(alpha: Float) {
        multiValueAlpha[INDEX_MODAL_ALPHA].value = alpha
    }

    override fun setFlexSplitAlpha(alpha: Float) {
        multiValueAlpha[INDEX_MINIMUM_RATIO_ALPHA].value = alpha
    }

    override fun getDrawableWidth(): Int = iconView?.drawableWidth ?: 0

    override fun getDrawableHeight(): Int = iconView?.drawableHeight ?: 0

    /** Gets the view split x-axis translation */
    fun getSplitTranslationX(): MultiPropertyFactory<View>.MultiProperty =
        viewTranslationX.get(INDEX_SPLIT_TRANSLATION)

    // sideways nudge for split tasks
    fun setSplitTranslationX(value: Float) {
        getSplitTranslationX().value = value
    }

    /** Gets the view split y-axis translation */
    fun getSplitTranslationY(): MultiPropertyFactory<View>.MultiProperty =
        viewTranslationY[INDEX_SPLIT_TRANSLATION]

    // split tasks need a vertical shove too
    fun setSplitTranslationY(value: Float) {
        getSplitTranslationY().value = value
    }

    /** Gets the menu x-axis translation for split task */
    fun getMenuTranslationX(): MultiPropertyFactory<View>.MultiProperty =
        viewTranslationX[INDEX_MENU_TRANSLATION]

    /** Gets the menu y-axis translation for split task */
    fun getMenuTranslationY(): MultiPropertyFactory<View>.MultiProperty =
        viewTranslationY[INDEX_MENU_TRANSLATION]

    internal fun revealAnim(isRevealing: Boolean, animated: Boolean = true): AnimatorSet {
        cancelInProgressAnimations()
        val collapsedBackgroundBounds = getCollapsedBackgroundLtrBounds()
        val expandedBackgroundBounds = getExpandedBackgroundLtrBounds()
        val initialBackground = Rect(backgroundRelativeLtrLocation)
        animator = AnimatorSet()

        val isRtl = isLayoutRtl
        if (isRevealing) {
            bringToFront()
            // Animate background clipping
            val backgroundAnimator =
                ValueAnimator.ofObject(
                    backgroundAnimationRectEvaluator,
                    initialBackground,
                    expandedBackgroundBounds,
                )
            backgroundAnimator.addUpdateListener {
                if (isBelowTask) updateBelowTaskIconPosition()
                invalidateOutline()
                invalidate()
            }

            val iconViewScaling = iconViewDrawableExpandedSize / appIconSize.toFloat()
            val iconCenterToTextCollapsed = appIconSize / 2f + appNameHorizontalMarginCollapsed
            val iconCenterToTextExpanded =
                iconViewDrawableExpandedSize / 2f + appNameHorizontalMarginCollapsed
            val textTranslationX = iconCenterToTextExpanded - iconCenterToTextCollapsed

            val textTranslationXWithRtl = if (isRtl) -textTranslationX else textTranslationX

            if (isBelowTask) {
                animator!!.playTogether(backgroundAnimator)
            } else {
                animator!!.playTogether(
                    backgroundAnimator,
                    ObjectAnimator.ofFloat(iconView, SCALE_X, iconViewScaling),
                    ObjectAnimator.ofFloat(iconView, SCALE_Y, iconViewScaling),
                    ObjectAnimator.ofFloat(appTitle, TRANSLATION_X, textTranslationXWithRtl),
                )
            }
            animator!!.duration = MENU_BACKGROUND_REVEAL_DURATION.toLong()
            status = AppChipStatus.Expanded
        } else {
            // Clip expanded text with reveal animation so it doesn't go beyond the edge of the menu
            val expandedTextClipAnim =
                ViewAnimationUtils.createCircularReveal(
                    appTitle,
                    if (isRtl) appTitle!!.width else 0,
                    appTitle!!.height / 2,
                    appTitle!!.width.toFloat(),
                    calculateCollapsedTextWidth(collapsedBackgroundBounds.width()).toFloat(),
                )

            // Animate background clipping
            val backgroundAnimator =
                ValueAnimator.ofObject(
                    backgroundAnimationRectEvaluator,
                    initialBackground,
                    collapsedBackgroundBounds,
                )
            backgroundAnimator.addUpdateListener {
                if (isBelowTask) updateBelowTaskIconPosition()
                invalidateOutline()
                invalidate()
            }

            if (isBelowTask) {
                animator!!.playTogether(backgroundAnimator)
            } else {
                animator!!.playTogether(
                    expandedTextClipAnim,
                    backgroundAnimator,
                    ObjectAnimator.ofFloat(iconView, SCALE_PROPERTY, 1f),
                    ObjectAnimator.ofFloat(appTitle, TRANSLATION_X, 0f),
                )
            }
            animator!!.duration = MENU_BACKGROUND_HIDE_DURATION.toLong()
            status = AppChipStatus.Collapsed
            sendToBack()
        }

        if (!animated) animator!!.duration = 0
        animator!!.interpolator =
            if (!isRevealing && isBelowTask) Interpolators.EMPHASIZED_ACCELERATE
            else Interpolators.EMPHASIZED

        // grow before opening, shrink after closing this thing
        animator!!.addListener(
            onStart = {
                // Hide focused border during expanding/collapsing animation
                if (isFocused) {
                    focusBorderAnimator.setBorderVisibility(visible = false, animated = false)
                }
                when (status) {
                    AppChipStatus.Expanded -> updateChipSize()
                    // Disable marquee before chip is collapsed
                    AppChipStatus.Collapsed -> enableMarquee(false)
                }
            },
            onEnd = {
                if (isFocused) animateFocusBorder(isAppearing = true)
                when (status) {
                    AppChipStatus.Collapsed -> updateChipSize()
                    // Enable marquee after chip is fully expanded
                    AppChipStatus.Expanded -> enableMarquee(true)
                }
            },
        )
        return animator!!
    }

    // name follows the chip width, not the other way round
    private fun updateChipSize() {
        if (isBelowTask) return
        val chipWidth = getChipWidth()
        when (status) {
            AppChipStatus.Expanded -> {
                updateLayoutParams { width = chipWidth }
                appTitle!!.updateLayoutParams { width = calculateExpandedTextWidth(chipWidth) }
            }
            AppChipStatus.Collapsed -> {
                appTitle!!.updateLayoutParams {
                    val collapsedBackgroundWidth = getCollapsedBackgroundLtrBounds().width()
                    width = calculateCollapsedTextWidth(collapsedBackgroundWidth)
                }
                updateLayoutParams { width = chipWidth }
            }
        }
    }

    private fun getCollapsedBackgroundLtrBounds(): Rect {
        // grid doesn't need the wide pill
        val width = if (iconOnlyMode) iconOnlyChipWidth else minimumWidth
        val bounds = Rect(0, 0, width, collapsedMenuDefaultHeight)
        bounds.offset(
            if (isBelowTask) (expandedMenuDefaultWidth - width) / 2 else backgroundMarginTopStart,
            backgroundMarginTopStart,
        )
        return bounds
    }

    private fun getExpandedBackgroundLtrBounds() =
        Rect(0, 0, expandedMenuDefaultWidth, expandedMenuDefaultHeight)

    private fun getCollapsedBackgroundWidth() = getCollapsedBackgroundLtrBounds().right

    private fun getChipWidth(): Int {
        // fake rotation needs the wide chip or the anchor goes sideways
        if (isBelowTask || !isLayoutNaturalToLauncher) return expandedMenuDefaultWidth
        return when (status) {
            AppChipStatus.Expanded -> expandedMenuDefaultWidth
            AppChipStatus.Collapsed -> getCollapsedBackgroundWidth()
        }
    }

    private fun cancelInProgressAnimations() {
        // reusing these animators crashes the damn thing
        if (animator != null && animator!!.isStarted) {
            animator!!.cancel()
            animator = null
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        z = iconMenuElevation + Z_INDEX_FRONT
        updateParentZIndex(Z_INDEX_FRONT)
    }

    private fun sendToBack() {
        z = iconMenuElevation
        updateParentZIndex(0f)
    }

    private fun updateParentZIndex(zIndex: Float) {
        val parentView = parent as? TaskView
        if (parentView?.isOnGridBottomRow == true) {
            parentView.z = zIndex
        }
    }

    override fun focusSearch(direction: Int): View? {
        if (mParent == null) return null
        return when (direction) {
            FOCUS_RIGHT,
            FOCUS_DOWN -> mParent.focusSearch(this, FOCUS_FORWARD)
            FOCUS_UP,
            FOCUS_LEFT -> mParent.focusSearch(this, FOCUS_BACKWARD)
            else -> super.focusSearch(direction)
        }
    }

    // live tile eats key events, so move focus ourselves
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val currentFocus = findFocus() ?: return super.dispatchKeyEvent(event)

        val nextFocus =
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> focusSearch(currentFocus, FOCUS_BACKWARD)
                KeyEvent.KEYCODE_DPAD_DOWN -> focusSearch(currentFocus, FOCUS_FORWARD)
                KeyEvent.KEYCODE_TAB ->
                    focusSearch(
                        currentFocus,
                        if (event.isShiftPressed) FOCUS_BACKWARD else FOCUS_FORWARD,
                    )
                else -> null
            }

        return nextFocus?.requestFocus() ?: super.dispatchKeyEvent(event)
    }

    fun reset() {
        setText(null)
        drawable = null
    }

    override fun asView(): View = this

    enum class AppChipStatus {
        Expanded,
        Collapsed,
    }

    private companion object {
        private val SUM_AGGREGATOR = FloatBiFunction { a: Float, b: Float -> a + b }

        private const val MENU_BACKGROUND_REVEAL_DURATION = 417
        private const val MENU_BACKGROUND_HIDE_DURATION = 333

        private const val Z_INDEX_FRONT = 10f

        private const val NUM_ALPHA_CHANNELS = 6
        private const val INDEX_CONTENT_ALPHA = 0
        private const val INDEX_COLOR_FILTER_ALPHA = 1
        private const val INDEX_MODAL_ALPHA = 2
        /** Used to hide the app chip for 90:10 flex split. */
        private const val INDEX_MINIMUM_RATIO_ALPHA = 3
        private const val INDEX_DISMISS_ALPHA = 4
        private const val INDEX_GRID_ALPHA = 5

        private const val INDEX_SPLIT_TRANSLATION = 0
        private const val INDEX_MENU_TRANSLATION = 1
        private const val INDEX_COUNT_TRANSLATION = 2
    }
}
