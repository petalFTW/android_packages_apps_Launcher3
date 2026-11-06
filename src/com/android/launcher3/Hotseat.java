/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.launcher3;

import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_BUBBLE_ADJUSTMENT_ANIM;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.launcher3.ShortcutAndWidgetContainer.TranslationProvider;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.petalos.AtelierSurface;
import com.android.launcher3.petalos.GlassDock;
import com.android.launcher3.util.HorizontalInsettableView;
import com.android.launcher3.util.LauncherBindableItemsContainer.ItemOperator;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.views.ActivityContext;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * View class that represents the bottom row of the home screen.
 */
public class Hotseat extends CellLayout implements Insettable {

    public static final int ALPHA_CHANNEL_TASKBAR_ALIGNMENT = 0;
    public static final int ALPHA_CHANNEL_PREVIEW_RENDERER = 1;
    public static final int ALPHA_CHANNEL_TASKBAR_STASH = 2;
    public static final int ALPHA_CHANNEL_ASSISTANT_VISIBILITY = 3;
    public static final int ALPHA_CHANNEL_CHANNELS_COUNT = 4;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ALPHA_CHANNEL_TASKBAR_ALIGNMENT, ALPHA_CHANNEL_PREVIEW_RENDERER,
            ALPHA_CHANNEL_TASKBAR_STASH, ALPHA_CHANNEL_ASSISTANT_VISIBILITY})
    public @interface HotseatQsbAlphaId {
    }

    public static final int ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT = 0;
    public static final int ICONS_TRANSLATION_X_CHANNELS_COUNT = 1;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT})
    public @interface IconsTranslationX {
    }

    // Ratio of empty space, qsb should take up to appear visually centered.
    public static final float QSB_CENTER_FACTOR = .325f;
    private static final int BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS = 250;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace<?> mWorkspace;
    private boolean mSendTouchToWorkspace;
    private final MultiValueAlpha mIconsAlphaChannels;
    private final MultiValueAlpha mQsbAlphaChannels;

    private @Nullable MultiProperty mQsbTranslationX;

    private final MultiPropertyFactory mIconsTranslationXFactory;

    private final View mQsb;

    // Petal Shelf follows the actual hotseat grid, including empty slots.
    // Glass preset gets the frosted dock; the others keep their Atelier material.
    private final GlassDock mGlassDock;
    private final AtelierSurface mAtelierDock;
    private boolean mGlassStyle = true;
    private boolean mLineStyle;
    private final android.graphics.Paint mLinePaint =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private float mLineShaderLeft = Float.NaN;
    private float mLineShaderRight;
    private final Rect mPetalDockRect = new Rect();
    private final Rect mPetalDockIconRect = new Rect();
    private boolean mPetalDockEnabled = true;

    public Hotseat(Context context) {
        this(context, null);
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (Flags.enableQsbOnHotseat()) {
            mQsb = LayoutInflater.from(context).inflate(R.layout.qsb_container_hotseat, this,
                    false);
        } else {
            mQsb = LayoutInflater.from(context).inflate(R.layout.search_container_hotseat, this,
                    false);
        }

        addView(mQsb);
        mIconsAlphaChannels = new MultiValueAlpha(getShortcutsAndWidgets(),
                ALPHA_CHANNEL_CHANNELS_COUNT);
        mIconsAlphaChannels.setUpdateVisibility(true);
        if (mQsb instanceof Reorderable qsbReorderable) {
            mQsbTranslationX = qsbReorderable.getTranslateDelegate()
                    .getTranslationX(MultiTranslateDelegate.INDEX_NAV_BAR_ANIM);
        }
        mIconsTranslationXFactory = new MultiPropertyFactory<>(getShortcutsAndWidgets(),
                VIEW_TRANSLATE_X, ICONS_TRANSLATION_X_CHANNELS_COUNT, Float::sum);
        mQsbAlphaChannels = new MultiValueAlpha(mQsb, ALPHA_CHANNEL_CHANNELS_COUNT);
        mQsbAlphaChannels.setUpdateVisibility(true);

        mGlassDock = new GlassDock(this);
        mAtelierDock = new AtelierSurface(this, false);
        String preset = PetalLauncherPrefs.preset(context);
        mGlassStyle = PetalLauncherPrefs.PRESET_GLASS.equals(preset);
        mLineStyle = PetalLauncherPrefs.PRESET_LINE.equals(preset);
        mPetalDockEnabled = PetalLauncherPrefs.enabled(context, PetalLauncherPrefs.DOCK_ENABLED);
    }

    /** petalOS: the Home setting just flips this, we draw the plate in dispatchDraw. */
    public void setPetalDockVisible(boolean enabled) {
        String preset = PetalLauncherPrefs.preset(getContext());
        boolean glass = PetalLauncherPrefs.PRESET_GLASS.equals(preset);
        boolean line = PetalLauncherPrefs.PRESET_LINE.equals(preset);
        if (mPetalDockEnabled != enabled || mGlassStyle != glass || mLineStyle != line) {
            mPetalDockEnabled = enabled;
            mGlassStyle = glass;
            mLineStyle = line;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // draw the plate first so it ends up under the icons
        drawPetalDock(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawPetalDock(Canvas canvas) {
        if (!mPetalDockEnabled) return;
        if (mHasVerticalHotseat || getCountX() == 0) return;
        cellToRect(0, 0, getCountX(), 1, mPetalDockRect);
        // drawable bounds matter here, the label's spare space doesn't
        int padH = getResources().getDimensionPixelSize(R.dimen.petal_dock_pad_h);
        int padV = getResources().getDimensionPixelSize(R.dimen.petal_dock_pad_v);
        mPetalDockRect.inset(-padH, -padV);
        int edge = getResources().getDimensionPixelSize(R.dimen.petal_shelf_screen_inset);
        if (mGlassStyle) {
            // iOS-style: one strip spanning the screen with an even margin, whatever the count.
            mPetalDockRect.left = edge;
            mPetalDockRect.right = getWidth() - edge;
        } else {
            // Atelier/Bloom keep the item centers and only clamp to the screen margin.
            mPetalDockRect.left = Math.max(edge, mPetalDockRect.left);
            mPetalDockRect.right = Math.min(getWidth() - edge, mPetalDockRect.right);
        }
        ShortcutAndWidgetContainer icons = getShortcutsAndWidgets();
        if (icons.getChildCount() > 0) {
            float centerY = 0;
            int iconHeight = 0;
            int visibleCount = 0;
            for (int i = 0; i < icons.getChildCount(); i++) {
                View icon = icons.getChildAt(i);
                if (icon.getVisibility() != View.VISIBLE) continue;
                if (icon instanceof BubbleTextView bubble) {
                    bubble.getIconBounds(mPetalDockIconRect);
                } else if (icon instanceof com.android.launcher3.folder.FolderIcon folder) {
                    folder.getPreviewBounds(mPetalDockIconRect);
                } else {
                    mPetalDockIconRect.set(0, 0, icon.getWidth(), icon.getHeight());
                }
                centerY += icons.getY() + icon.getY() + mPetalDockIconRect.exactCenterY();
                iconHeight = Math.max(iconHeight, mPetalDockIconRect.height());
                visibleCount++;
            }
            if (visibleCount > 0) {
                centerY /= visibleCount;
                // shrink symmetrically if space is tight; clamping the bottom used to shift it up
                float halfHeight = Math.min(iconHeight / 2f + padV,
                        Math.min(centerY, getHeight() - centerY));
                if (halfHeight <= 0f) return;
                mPetalDockRect.top = Math.round(centerY - halfHeight);
                mPetalDockRect.bottom = Math.round(centerY + halfHeight);
            }
        }
        if (mPetalDockRect.top < 0) mPetalDockRect.offset(0, -mPetalDockRect.top);
        if (mPetalDockRect.bottom > getHeight()) {
            mPetalDockRect.offset(0, getHeight() - mPetalDockRect.bottom);
        }
        if (!mPetalDockRect.intersect(0, 0, getWidth(), getHeight())) return;
        if (mLineStyle) {
            drawDockLine(canvas);
        } else if (mGlassStyle) {
            mGlassDock.draw(canvas, mPetalDockRect.left, mPetalDockRect.top,
                    mPetalDockRect.right, mPetalDockRect.bottom,
                    getResources().getDimension(R.dimen.petal_dock_radius));
        } else {
            mAtelierDock.draw(canvas, mPetalDockRect.left, mPetalDockRect.top,
                    mPetalDockRect.width(), mPetalDockRect.height(),
                    mAtelierDock.getShelfRadius(mPetalDockRect.height()), 255);
        }
    }

    // Line preset: a hairline over the dock icons, fading out at both ends.
    private void drawDockLine(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float left = getResources().getDimensionPixelSize(R.dimen.petal_workspace_side_inset);
        float right = getWidth() - left;
        if (right <= left) return;
        if (mLineShaderLeft != left || mLineShaderRight != right) {
            mLineShaderLeft = left;
            mLineShaderRight = right;
            boolean dark = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            int ink = dark ? 0x66FFFFFF : 0x40000000;
            mLinePaint.setShader(new android.graphics.LinearGradient(left, 0, right, 0,
                    new int[] {ink & 0x00FFFFFF, ink, ink, ink & 0x00FFFFFF},
                    new float[] {0f, .2f, .8f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            mLinePaint.setStrokeWidth(Math.max(1f, density * .75f));
        }
        float y = mPetalDockRect.top;
        canvas.drawLine(left, y, right, y, mLinePaint);
    }

    /** Provides translation X for hotseat icons for the channel. */
    public MultiProperty getIconsTranslationX(@IconsTranslationX int channelId) {
        return mIconsTranslationXFactory.get(channelId);
    }

    /** Provides translation X for hotseat Qsb. */
    @Nullable
    public MultiProperty getQsbTranslationX() {
        return mQsbTranslationX;
    }

    /**
     * Returns orientation specific cell X given invariant order in the hotseat
     */
    public int getCellXFromOrder(int rank) {
        return mHasVerticalHotseat ? 0 : rank;
    }

    /**
     * Returns orientation specific cell Y given invariant order in the hotseat
     */
    public int getCellYFromOrder(int rank) {
        return mHasVerticalHotseat ? (getCountY() - (rank + 1)) : 0;
    }

    boolean isHasVerticalHotseat() {
        return mHasVerticalHotseat;
    }

    public void resetLayout(boolean hasVerticalHotseat) {
        ActivityContext activityContext = ActivityContext.lookupContext(getContext());
        boolean bubbleBarEnabled = activityContext.isBubbleBarEnabled();
        boolean hasBubbles = activityContext.hasBubbles();
        removeAllViewsInLayout();
        mHasVerticalHotseat = hasVerticalHotseat;
        DeviceProfile dp = mActivity.getDeviceProfile();

        if (bubbleBarEnabled) {
            if (dp.shouldAdjustHotseatForBubbleBar(getContext(), hasBubbles)) {
                getShortcutsAndWidgets().setTranslationProvider(
                        cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
                if (mQsb instanceof HorizontalInsettableView) {
                    HorizontalInsettableView insettableQsb = (HorizontalInsettableView) mQsb;
                    final float insetFraction =
                            (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth;
                    // post this to the looper so that QSB has a chance to redraw itself, e.g.
                    // after device rotation
                    mQsb.post(() -> insettableQsb.setHorizontalInsets(insetFraction));
                }
            } else {
                getShortcutsAndWidgets().setTranslationProvider(null);
                if (mQsb instanceof HorizontalInsettableView) {
                    ((HorizontalInsettableView) mQsb).setHorizontalInsets(0);
                }
            }
        }

        resetCellSize(dp);
        if (hasVerticalHotseat) {
            setGridSize(1, dp.numShownHotseatIcons);
        } else {
            setGridSize(dp.numShownHotseatIcons, 1);
        }
    }

    /**
     * Adjust the hotseat icons for the bubble bar.
     *
     * <p>When the bubble bar becomes visible, if needed, this method animates the hotseat icons
     * to reduce the spacing between them and make room for the bubble bar. The QSB width is
     * animated as well to align with the hotseat icons.
     *
     * <p>When the bubble bar goes away, any adjustments that were previously made are reversed.
     */
    public void adjustForBubbleBar(boolean isBubbleBarVisible) {
        DeviceProfile dp = mActivity.getDeviceProfile();
        boolean shouldAdjust = isBubbleBarVisible
                && dp.shouldAdjustHotseatOrQsbForBubbleBar(getContext());
        boolean shouldAdjustHotseat = shouldAdjust
                && dp.shouldAlignBubbleBarWithHotseat();
        ShortcutAndWidgetContainer icons = getShortcutsAndWidgets();
        // update the translation provider for future layout passes of hotseat icons.
        if (shouldAdjustHotseat) {
            icons.setTranslationProvider(
                    cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
        } else {
            icons.setTranslationProvider(null);
        }
        AnimatorSet animatorSet = new AnimatorSet();
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getLayoutParams() instanceof CellLayoutLayoutParams lp) {
                float tx = shouldAdjustHotseat
                        ? dp.getHotseatAdjustedTranslation(getContext(), lp.getCellX()) : 0;
                if (child instanceof Reorderable) {
                    MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();
                    animatorSet.play(
                            mtd.getTranslationX(INDEX_BUBBLE_ADJUSTMENT_ANIM).animateToValue(tx));
                } else {
                    animatorSet.play(ObjectAnimator.ofFloat(child, VIEW_TRANSLATE_X, tx));
                }
            }
        }
        //TODO(b/381109832) refactor & simplify adjustment logic
        boolean shouldAdjustQsb =
                shouldAdjustHotseat || (shouldAdjust && dp.shouldAlignBubbleBarWithQSB());
        if (mQsb instanceof HorizontalInsettableView horizontalInsettableQsb) {
            final float currentInsetFraction = horizontalInsettableQsb.getHorizontalInsets();
            final float targetInsetFraction = shouldAdjustQsb
                    ? (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth : 0;
            ValueAnimator qsbAnimator =
                    ValueAnimator.ofFloat(currentInsetFraction, targetInsetFraction);
            qsbAnimator.addUpdateListener(animation -> {
                float insetFraction = (float) animation.getAnimatedValue();
                horizontalInsettableQsb.setHorizontalInsets(insetFraction);
            });
            animatorSet.play(qsbAnimator);
        }
        animatorSet.setDuration(BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS).start();
    }

    @Override
    protected int getTranslationXForCell(int cellX, int cellY) {
        TranslationProvider translationProvider = getShortcutsAndWidgets().getTranslationProvider();
        if (translationProvider == null) return 0;
        return (int) translationProvider.getTranslationX(cellX);
    }

    @Override
    public void setInsets(Rect insets) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        DeviceProfile grid = mActivity.getDeviceProfile();
        lp.bottomMargin = grid.getWorkspaceIconProfile().getAtelierDockLiftPx();

        if (grid.isVerticalBarLayout()) {
            mQsb.setVisibility(View.GONE);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (grid.isSeascape()) {
                lp.gravity = Gravity.LEFT;
                lp.width = grid.hotseatBarSizePx + insets.left;
            } else {
                lp.gravity = Gravity.RIGHT;
                lp.width = grid.hotseatBarSizePx + insets.right;
            }
        } else {
            mQsb.setVisibility(View.VISIBLE);
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = grid.hotseatBarSizePx;
        }

        Rect padding = new Rect(grid.getHotseatLayoutPadding(getContext()));
        if (!grid.isVerticalBarLayout()) {
            // The grid leaves spare space below this row for navigation. Use some of that space
            // to center full-size icons inside a comfortably padded shelf, rather than clipping
            // the plate at the Hotseat's top edge or shrinking the artwork to make it fit.
            int shelfPadding = getResources().getDimensionPixelSize(R.dimen.petal_dock_pad_v);
            int shift = Math.min(shelfPadding, padding.bottom);
            padding.top += shift;
            padding.bottom -= shift;
        }
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace<?> w) {
        mWorkspace = w;
        setCellLayoutContainer(w);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // We allow horizontal workspace scrolling from within the Hotseat. We do this by delegating
        // touch intercept the Workspace, and if it intercepts, delegating touch to the Workspace
        // for the remainder of the this input stream.
        int yThreshold = getMeasuredHeight() - getPaddingBottom();
        if (mWorkspace != null && ev.getY() <= yThreshold) {
            mSendTouchToWorkspace = mWorkspace.onInterceptTouchEvent(ev);
            return mSendTouchToWorkspace;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // See comment in #onInterceptTouchEvent
        if (mSendTouchToWorkspace) {
            final int action = event.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSendTouchToWorkspace = false;
            }
            return mWorkspace.onTouchEvent(event);
        }
        // Always let touch follow through to Workspace.
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        DeviceProfile dp = mActivity.getDeviceProfile();
        mQsb.measure(makeMeasureSpec(dp.hotseatQsbWidth, MeasureSpec.EXACTLY),
                makeMeasureSpec(dp.getHotseatProfile().getQsbHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        int qsbMeasuredWidth = mQsb.getMeasuredWidth();
        int left;
        DeviceProfile dp = mActivity.getDeviceProfile();
        if (dp.isQsbInline) {
            int qsbSpace = dp.hotseatBorderSpace;
            left = Utilities.isRtl(getResources()) ? r - getPaddingRight() + qsbSpace
                    : l + getPaddingLeft() - qsbMeasuredWidth - qsbSpace;
        } else {
            left = (r - l - qsbMeasuredWidth) / 2;
        }
        int right = left + qsbMeasuredWidth;

        int bottom = b - t - dp.getQsbOffsetY();
        int top = bottom - dp.getHotseatProfile().getQsbHeight();
        mQsb.layout(left, top, right, bottom);
    }

    /**
     * Sets the alpha value of the specified alpha channel of just our ShortcutAndWidgetContainer.
     */
    public void setIconsAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getIconsAlpha(channelId).setValue(alpha);
    }

    /**
     * Sets the alpha value of just our QSB.
     */
    public void setQsbAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getQsbAlpha(channelId).setValue(alpha);
    }

    /** Returns the alpha channel for ShortcutAndWidgetContainer */
    public MultiProperty getIconsAlpha(@HotseatQsbAlphaId int channelId) {
        return mIconsAlphaChannels.get(channelId);
    }

    /** Returns the alpha channel for Qsb */
    public MultiProperty getQsbAlpha(@HotseatQsbAlphaId int channelId) {
        return mQsbAlphaChannels.get(channelId);
    }

    /**
     * Returns the QSB inside hotseat
     */
    public View getQsb() {
        return mQsb;
    }

    @Nullable
    @Override
    public View mapOverItems(ItemOperator op) {
        if (Flags.enableQsbOnHotseat()
                && mQsb != null
                && mQsb.getTag() instanceof ItemInfo info
                && op.evaluate(info, mQsb)) {
            return mQsb;
        }
        return super.mapOverItems(op);
    }

    /** Dumps the Hotseat internal state */
    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "Hotseat:");
        mIconsAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mIconsAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH");
        mQsbAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mQsbAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH"
        );
    }

}
