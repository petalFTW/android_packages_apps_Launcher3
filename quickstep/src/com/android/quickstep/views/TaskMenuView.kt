/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.android.app.animation.Interpolators
import com.android.app.animation.Interpolators.clampToProgress
import com.android.internal.graphics.drawable.PetalGlassDrawable
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Flags.enableRefactorTaskThumbnail
import com.android.launcher3.R
import com.android.launcher3.anim.AnimationSuccessListener
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.util.MultiPropertyFactory
import com.android.launcher3.util.OverviewReleaseFlags.enableOverviewIconMenu
import com.android.launcher3.util.SplitConfigurationOptions
import com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_UNDEFINED
import com.android.launcher3.views.BaseDragLayer
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.TaskUtils
import com.android.quickstep.orientation.RecentsPagedOrientationHandler
import com.android.quickstep.util.TaskCornerRadius
import kotlin.math.max
import kotlin.math.roundToInt

/** Contains options for a recent task when long-pressing its icon. */
class TaskMenuView
@JvmOverloads
constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int = 0) :
    AbstractFloatingView(context, attrs, defStyleAttr) {
    private val recentsViewContainer: RecentsViewContainer =
        RecentsViewContainer.containerFromContext(context)
    private val tempRect = Rect()
    private val menuCornerRadius = resources.getDimension(R.dimen.task_menu_corner_radius)
    private val menuGlass =
        PetalGlassDrawable(this, menuCornerRadius / resources.displayMetrics.density, 0f, false)
    private val taskName: TextView by lazy { findViewById(R.id.task_name) }
    private val optionLayout: LinearLayout by lazy { findViewById(R.id.menu_option_layout) }
    private var openCloseAnimator: AnimatorSet? = null
    private var revealAnimator: ValueAnimator? = null
    private var onClosingStartCallback: Runnable? = null
    private lateinit var orientationHandler: RecentsPagedOrientationHandler
    private lateinit var taskTarget: TaskTarget
    private val taskView: TaskView
        get() = taskTarget.taskView

    private val taskContainer: TaskContainer?
        get() = (taskTarget as? TaskTarget.FromTaskContainer)?.container

    private val containerView: View
        get() = taskContainer?.snapshotView ?: taskView

    private val stagePosition: Int
        get() = taskContainer?.stagePosition ?: STAGE_POSITION_UNDEFINED

    private var menuTranslationXBeforeOpen = 0f
    private var menuTranslationYBeforeOpen = 0f

    // Spaced claimed below Overview (taskbar and insets)
    private val taskbarTop by lazy {
        recentsViewContainer.deviceProfile.deviceProperties.heightPx -
            recentsViewContainer.deviceProfile.overviewActionsClaimedSpaceBelow
    }
    private val minMenuTop by lazy { iconView.height.toFloat() }
    private val maxMenuBottom by lazy { taskbarTop - recentsViewContainer.dragLayer.insets.top }

    init {
        clipToOutline = true
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (!recentsViewContainer.dragLayer.isEventOverView(this, ev)) {
                // TODO: log this once we have a new container type for it?
                animateOpenOrClosed(true)
                return true
            }
        }
        return false
    }

    override fun handleClose(animate: Boolean) {
        animateOpenOrClosed(closing = true, animated = animate)
    }

    override fun isOfType(type: Int): Boolean = (type and TYPE_TASK_MENU) != 0

    override fun getOutlineProvider(): ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    if (enableOverviewIconMenu()) menuCornerRadius
                    else TaskCornerRadius.get(view.context),
                )
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightMeasure = heightMeasureSpec
        val maxMenuHeight = calculateMaxHeight()
        if (MeasureSpec.getSize(heightMeasure) > maxMenuHeight) {
            heightMeasure = MeasureSpec.makeMeasureSpec(maxMenuHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, heightMeasure)
    }

    fun onRotationChanged() {
        openCloseAnimator?.let { if (it.isRunning) it.end() }
        if (mIsOpen) {
            optionLayout.removeAllViews()
            if (enableOverviewIconMenu() || !populateAndLayoutMenu()) {
                close(false)
            }
        }
    }

    private fun populateAndShowForTask(taskTarget: TaskTarget): Boolean {
        if (isAttachedToWindow) return false
        recentsViewContainer.dragLayer.addView(this)
        this.taskTarget = taskTarget
        if (!populateAndLayoutMenu()) return false
        post { this.animateOpen() }
        return true
    }

    /** @return true if successfully able to populate task view menu, false otherwise */
    private fun populateAndLayoutMenu(): Boolean {
        addMenuOptions()
        orientAroundTaskView()
        return true
    }

    private fun addMenuOptions() {
        if (enableOverviewIconMenu()) {
            removeView(taskName)
        } else {
            taskName.text = TaskUtils.getTitle(context, taskContainer?.task)
            taskName.setOnClickListener { close(true) }
        }
        TaskOverlayFactory.getEnabledShortcuts(taskView, taskContainer).forEach {
            menuOption: SystemShortcut<*> ->
            addMenuOption(menuOption)
        }
    }

    private fun addMenuOption(menuOption: SystemShortcut<*>) {
        val menuOptionView =
            recentsViewContainer.layoutInflater.inflate(R.layout.task_view_menu_option, this, false)
                as LinearLayout
        if (enableOverviewIconMenu()) {
            menuOptionView.background =
                ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.app_chip_menu_item_bg,
                    context.theme,
                )
        }
        menuOption.setIconAndLabelFor(
            menuOptionView.findViewById(R.id.icon),
            menuOptionView.findViewById(R.id.text),
        )
        val lp = menuOptionView.layoutParams as LayoutParams
        taskView.pagedOrientationHandler.setLayoutParamsForTaskMenuOptionItem(
            lp,
            menuOptionView,
            recentsViewContainer.deviceProfile,
        )
        // Set an onClick listener on each menu option. The onClick method is responsible for
        // ending LiveTile mode on the thumbnail if needed.
        menuOptionView.setOnClickListener { v: View? -> menuOption.onClick(v) }
        optionLayout.addView(menuOptionView)
    }

    private fun orientAroundTaskView() {
        val recentsView = recentsViewContainer.getOverviewPanel<RecentsView<*, *>>()
        orientationHandler = recentsView.pagedOrientationHandler
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)

        // Get Position
        val deviceProfile = recentsViewContainer.deviceProfile
        recentsViewContainer.dragLayer.getDescendantRectRelativeToSelf(
            if (enableOverviewIconMenu()) iconView.findViewById(R.id.icon_view_menu_anchor)
            else containerView,
            tempRect,
        )
        val insets = recentsViewContainer.dragLayer.insets
        val params = layoutParams as BaseDragLayer.LayoutParams
        params.width =
            orientationHandler.getTaskMenuWidth(containerView, deviceProfile, stagePosition)
        // Gravity set to Left instead of Start as sTempRect.left measures Left distance not Start
        params.gravity =
            if (enableOverviewIconMenu() && orientationHandler.isLayoutNaturalToLauncher &&
                !isAppChipBelowTask)
                Gravity.START
            else Gravity.LEFT
        layoutParams = params
        scaleX = taskView.scaleX
        scaleY = taskView.scaleY

        // Set divider spacing
        val divider = ShapeDrawable(RectShape())
        divider.paint.color = resources.getColor(android.R.color.transparent)
        val dividerSpacing = resources.getDimension(R.dimen.task_menu_spacing).toInt()
        optionLayout.showDividers =
            if (enableOverviewIconMenu()) SHOW_DIVIDER_NONE else SHOW_DIVIDER_MIDDLE

        optionLayout.background = null
        if (enableOverviewIconMenu()) {
            background = menuGlass
        }

        orientationHandler.setTaskOptionsMenuLayoutOrientation(
            deviceProfile,
            optionLayout,
            dividerSpacing,
            divider,
        )

        val thumbnailAlignedX =
            if (
                enableOverviewIconMenu() &&
                    orientationHandler.isLayoutNaturalToLauncher &&
                    isLayoutRtl
            )
                -(recentsViewContainer.dragLayer.width - tempRect.right - insets.right).toFloat()
            else (tempRect.left - insets.left).toFloat()
        val thumbnailAlignedY = (tempRect.top - insets.top).toFloat()

        // Changing pivot to make computations easier
        // NOTE: Changing the pivots means the rotated view gets rotated about the new pivots set,
        // which would render the X and Y position set here incorrect
        pivotX = 0f
        pivotY = 0f
        rotation = orientationHandler.degreesRotated

        var taskInsetMargin = resources.getDimension(R.dimen.task_card_margin)
        if (enableOverviewIconMenu()) {
            elevation = resources.getDimension(R.dimen.task_thumbnail_icon_menu_elevation)
            taskInsetMargin = 0f
        }

        translationX =
            if (isAppChipBelowTask) {
                (recentsViewContainer.dragLayer.width - params.width * scaleX) / 2f - insets.left
            } else {
                orientationHandler.getTaskMenuX(
                    thumbnailAlignedX,
                    containerView,
                    deviceProfile,
                    taskInsetMargin,
                    iconView,
                )
            }
        translationY =
            orientationHandler.getTaskMenuY(
                thumbnailAlignedY,
                containerView,
                stagePosition,
                this,
                taskInsetMargin,
                iconView,
            )
    }

    private fun animateOpen() {
        menuTranslationYBeforeOpen = translationY
        menuTranslationXBeforeOpen = translationX
        animateOpenOrClosed(closing = false)
        mIsOpen = true
    }

    private val iconView: View
        get() = taskContainer?.iconView?.asView() ?: taskView.getTaskIcons().first().first.asView()

    private val isAppChipBelowTask: Boolean
        get() = (iconView as? IconAppChipView)?.isBelowTask == true

    private fun animateOpenOrClosed(closing: Boolean, animated: Boolean = true) {
        if (!iconView.isAttachedToWindow) return
        openCloseAnimator?.let { if (it.isRunning) it.cancel() }
        // opening gets a fresh menu, no half-finished animation crap
        var revealAnimationStartProgress = 0f
        if (closing) revealAnimator?.let { revealAnimationStartProgress = 1f - it.animatedFraction }
        revealAnimator =
            createOpenCloseOutlineProvider()
                .createRevealAnimator(this, closing, revealAnimationStartProgress)
                .apply {
                    interpolator =
                        if (closing && isAppChipBelowTask) Interpolators.EMPHASIZED_ACCELERATE
                        else if (enableOverviewIconMenu()) Interpolators.EMPHASIZED
                        else Interpolators.DECELERATE

                    if (enableRefactorTaskThumbnail()) {
                        addUpdateListener { animation: ValueAnimator ->
                            val animatedFraction = animation.animatedFraction
                            val openProgress =
                                if (closing) (1 - animatedFraction) else animatedFraction
                            taskContainer?.updateMenuOpenProgress(openProgress)
                        }
                    }
                }
        openCloseAnimator =
            AnimatorSet()
                .apply {
                    duration =
                        when {
                            animated && closing -> REVEAL_CLOSE_DURATION
                            animated && !closing -> REVEAL_OPEN_DURATION
                            else -> 0L
                        }
                    addListener(
                        object : AnimationSuccessListener() {
                            override fun onAnimationStart(animation: Animator) {
                                visibility = VISIBLE
                                if (closing) onClosingStartCallback?.run()
                            }

                            override fun onAnimationSuccess(animator: Animator) {
                                if (closing) closeComplete()
                            }
                        }
                    )
                }
                .also { animator ->
                    val animatorBuilder = animator.play(revealAnimator)
                    if (enableOverviewIconMenu()) {
                        animateOpenOrCloseAppChip(closing, animatorBuilder)
                    } else {
                        animatorBuilder.with(
                            ObjectAnimator.ofFloat(this, ALPHA, (if (closing) 0 else 1).toFloat())
                        )
                    }

                    if (!enableRefactorTaskThumbnail()) {
                        taskContainer?.let {
                            animatorBuilder.with(
                                ObjectAnimator.ofFloat(
                                    it.thumbnailViewDeprecated,
                                    TaskThumbnailViewDeprecated.DIM_ALPHA,
                                    if (closing) 0f else TaskView.MAX_PAGE_SCRIM_ALPHA,
                                )
                            )
                        }
                    }

                    animator.start()
                }
    }

    private fun TaskView.isOnGridBottomRow(): Boolean =
        (recentsViewContainer.getOverviewPanel<View>() as RecentsView<*, *>).isOnGridBottomRow(this)

    private fun closeComplete() {
        mIsOpen = false
        recentsViewContainer.dragLayer.removeView(this)
        revealAnimator = null
    }

    private fun createOpenCloseOutlineProvider(): RoundedRectRevealOutlineProvider {
        val radius =
            if (enableOverviewIconMenu()) menuCornerRadius else TaskCornerRadius.get(mContext)

        val fromRect =
            if (isAppChipBelowTask) {
                // actually fold into the pill, don't leave most of the panel hanging there
                val left = (width - iconView.minimumWidth) / 2
                Rect(left, 0, left + iconView.minimumWidth, 0)
            } else if (enableOverviewIconMenu()) {
                Rect(
                    /* left = */ if (isLayoutRtl) width - iconView.minimumWidth else 0,
                    /* top = */ 0,
                    /* right = */ if (isLayoutRtl) width else iconView.minimumWidth,
                    /* bottom = */ (height * CONTAINER_SCALE_PERCENTAGE).roundToInt(),
                )
            } else {
                Rect(0, 0, width, 0)
            }

        val toRect = Rect(0, 0, width, height)
        return RoundedRectRevealOutlineProvider(radius, radius, fromRect, toRect)
    }

    // scroll the options if this thing won't fit
    private fun calculateMaxHeight(): Int =
        taskView.pagedOrientationHandler.getTaskMenuHeight(
            taskInsetMargin = resources.getDimension(R.dimen.task_card_margin), // taskInsetMargin
            deviceProfile = recentsViewContainer.deviceProfile,
            taskMenuX = translationX,
            taskMenuY =
                // bottom pills need room to shove the menu upward
                if (
                    enableOverviewIconMenu() &&
                        (taskView.isOnGridBottomRow() || isAppChipBelowTask)
                ) {
                    minMenuTop
                } else {
                    translationY
                },
        )

    private fun setOnClosingStartCallback(onClosingStartCallback: Runnable?) {
        this.onClosingStartCallback = onClosingStartCallback
    }

    private fun animateOpenOrCloseAppChip(closing: Boolean, animatorBuilder: AnimatorSet.Builder) {
        val movementInterpolator =
            if (closing && isAppChipBelowTask) Interpolators.EMPHASIZED_ACCELERATE
            else Interpolators.EMPHASIZED
        if (!closing) {
            alpha = 0f
            optionLayout.apply {
                pivotX = if (isLayoutRtl) optionLayout.width.toFloat() else 0f
                pivotY = 0f
                scaleX = CONTAINER_SCALE_PERCENTAGE
                scaleY = CONTAINER_SCALE_PERCENTAGE
                alpha = 0f
            }
        }

        val iconAppChip = iconView as IconAppChipView

        // shove the pill up so the options can take its old spot
        var additionalTranslationY = 0f
        val translationYMargin = orientationHandler.getAppChipMenuMarginY(iconAppChip, isLayoutRtl)
        if (taskView.isOnGridBottomRow() || isAppChipBelowTask) {
            val expandedMenuPosition = menuTranslationYBeforeOpen + translationYMargin
            val currentMenuBottom: Float = expandedMenuPosition + height
            val menuBottom =
                if (isAppChipBelowTask) menuTranslationYBeforeOpen else maxMenuBottom.toFloat()
            additionalTranslationY =
                if (currentMenuBottom < menuBottom) 0f
                else menuBottom - currentMenuBottom

            val currentMenuTop = expandedMenuPosition + additionalTranslationY
            // If it translate above the min accepted, it translates to the top of the screen
            if (currentMenuTop < minMenuTop) {
                // It subtracts the menuTranslation to make it 0 (top of the screen) + chip size.
                additionalTranslationY = -expandedMenuPosition + minMenuTop
            }
        }

        val translationYAnim =
            ObjectAnimator.ofFloat(
                this,
                TRANSLATION_Y,
                if (closing) menuTranslationYBeforeOpen
                else menuTranslationYBeforeOpen + translationYMargin + additionalTranslationY,
            )
        translationYAnim.interpolator = movementInterpolator
        animatorBuilder.with(translationYAnim)

        val menuTranslationYAnim: ObjectAnimator =
            ObjectAnimator.ofFloat(
                iconAppChip.getMenuTranslationY(),
                MultiPropertyFactory.MULTI_PROPERTY_VALUE,
                if (closing) 0f else additionalTranslationY,
            )
        menuTranslationYAnim.interpolator = movementInterpolator
        animatorBuilder.with(menuTranslationYAnim)

        var additionalTranslationX = 0f
        if (
            !isAppChipBelowTask &&
            taskView.pagedOrientationHandler.isLayoutNaturalToLauncher &&
                taskContainer?.stagePosition ==
                    SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT
        ) {
            // Animate menu and icon when split task would display off the side of the screen.
            additionalTranslationX =
                max(
                        (translationX + width -
                                (recentsViewContainer.deviceProfile.deviceProperties.widthPx -
                                    resources.getDimensionPixelSize(
                                        R.dimen.task_menu_edge_padding
                                    ) * 2))
                            .toDouble(),
                        0.0,
                    )
                    .toFloat()
        }

        val translationXMargin = orientationHandler.getAppChipMenuMarginX(iconAppChip, isLayoutRtl)
        val translationXAnim =
            ObjectAnimator.ofFloat(
                this,
                TRANSLATION_X,
                if (closing) menuTranslationXBeforeOpen
                else if (isAppChipBelowTask) {
                    (recentsViewContainer.dragLayer.width - width * scaleX) / 2f -
                        recentsViewContainer.dragLayer.insets.left
                }
                else menuTranslationXBeforeOpen - translationXMargin - additionalTranslationX,
            )
        translationXAnim.interpolator = movementInterpolator
        animatorBuilder.with(translationXAnim)

        val menuTranslationXAnim: ObjectAnimator =
            ObjectAnimator.ofFloat(
                iconAppChip.getMenuTranslationX(),
                MultiPropertyFactory.MULTI_PROPERTY_VALUE,
                if (closing) 0f else -additionalTranslationX,
            )
        menuTranslationXAnim.interpolator = movementInterpolator
        animatorBuilder.with(menuTranslationXAnim)

        // Scaling the container inside the menu
        val toScaleX = if (closing) CONTAINER_SCALE_PERCENTAGE else 1f
        val animatorScaleX = ObjectAnimator.ofFloat(optionLayout, SCALE_X, toScaleX)
        animatorScaleX.interpolator = movementInterpolator
        animatorBuilder.with(animatorScaleX)

        val toScaleY = if (closing) CONTAINER_SCALE_PERCENTAGE else 1f
        val animatorScaleY = ObjectAnimator.ofFloat(optionLayout, SCALE_Y, toScaleY)
        animatorScaleY.interpolator = movementInterpolator
        animatorBuilder.with(animatorScaleY)

        val alphaValue = if (closing) 0f else 1f
        val optionLayoutAlphaAnimator =
            ObjectAnimator.ofFloat(optionLayout, ALPHA, alphaValue).apply {
                interpolator =
                    if (isAppChipBelowTask) Interpolators.LINEAR
                    else TimeInterpolator {
                        clampToProgress(Interpolators.EMPHASIZED.getInterpolation(it), .75f, 1f)
                    }
            }
        animatorBuilder.with(optionLayoutAlphaAnimator)

        val menuAlphaAnimator =
            ObjectAnimator.ofFloat(this, ALPHA,
                if (closing && !isAppChipBelowTask) 0f else 1f).apply {
                interpolator =
                    if (isAppChipBelowTask) Interpolators.LINEAR
                    else TimeInterpolator {
                        clampToProgress(Interpolators.EMPHASIZED.getInterpolation(it), .48f, .74f)
                    }
            }
        animatorBuilder.with(menuAlphaAnimator)

        val recentsView = recentsViewContainer.getOverviewPanel<RecentsView<*, *>>()
        val isAnimated = !recentsView.isSplitSelectionActive
        animatorBuilder.with(iconAppChip.revealAnim(isRevealing = !closing, isAnimated))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (enableOverviewIconMenu()) {
            if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

            val isFirstMenuOptionFocused = optionLayout.indexOfChild(optionLayout.focusedChild) == 0
            val isLastMenuOptionFocused =
                optionLayout.indexOfChild(optionLayout.focusedChild) == optionLayout.childCount - 1
            if (
                (isLastMenuOptionFocused && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ||
                    (isFirstMenuOptionFocused && event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
            ) {
                iconView.requestFocus()
                return true
            } else {
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
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private val REVEAL_OPEN_DURATION = if (enableOverviewIconMenu()) 417L else 150L
        private val REVEAL_CLOSE_DURATION = if (enableOverviewIconMenu()) 333L else 100L
        private const val CONTAINER_SCALE_PERCENTAGE = .8f

        /** Show a task menu for the given task. */
        fun showForTask(taskTarget: TaskTarget, onClosingStartCallback: Runnable? = null): Boolean {
            val container: RecentsViewContainer =
                RecentsViewContainer.containerFromContext(taskTarget.context)
            val taskMenuView =
                container.layoutInflater.inflate(R.layout.task_menu, container.dragLayer, false)
                    as TaskMenuView
            taskMenuView.setOnClosingStartCallback(onClosingStartCallback)
            return taskMenuView.populateAndShowForTask(taskTarget)
        }
    }

    /** An identifier for a task, which can be either a [TaskContainer] or a [TaskView]. */
    sealed class TaskTarget {
        abstract val taskView: TaskView
        val context: Context
            get() = taskView.context

        data class FromTaskContainer(val container: TaskContainer) : TaskTarget() {
            override val taskView: TaskView
                get() = container.taskView
        }

        data class FromTaskView(override val taskView: TaskView) : TaskTarget()
    }
}
