/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import org.petalos.config.DepthClockRenderer;
import org.petalos.config.PetalConfig;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Depth Studio on Home: the depth clock and the subject cutout drawn behind the workspace, so
 * the order is wallpaper, clock, subject, icons. Layout matches the lock screen clock exactly,
 * which is what lets the unlock fade hand the clock over without it moving.
 */
public final class DepthHomeLayer extends View {
    private static final String TAG = "DepthHomeLayer";
    private static final String DATE_FORMAT = "EEE d MMMM";
    // Home keeps its own photo, cutout and clock (Depth Studio's Home screen section).
    private static final String[] KEYS = {
            PetalConfig.KEY_DEPTH_GENERATION, PetalConfig.KEY_DEPTH_CUTOUT_URI,
            PetalConfig.KEY_DEPTH_CLOCK_STYLE, PetalConfig.KEY_DEPTH_CLOCK_STACK,
            PetalConfig.KEY_DEPTH_CLOCK_COLOR, PetalConfig.KEY_DEPTH_CLOCK_DATE,
            PetalConfig.KEY_DEPTH_CLOCK_ANCHOR, PetalConfig.KEY_DEPTH_CLOCK_X,
            PetalConfig.KEY_DEPTH_CLOCK_SCALE, PetalConfig.KEY_DEPTH_CLOCK_COLON,
    };

    private final DepthClockRenderer mClock = new DepthClockRenderer();
    private final Paint mSubjectPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF mSubjectRect = new RectF();
    private final Calendar mCalendar = Calendar.getInstance();
    private final int[] mScreenPosition = new int[2];
    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh();
                }
            };
    private final BroadcastReceiver mTimeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                refresh();
            } else {
                updateTime();
            }
        }
    };

    // sysui says unlock started: sink in step with the lock clock, then rise back. never goes see-through
    private final BroadcastReceiver mHandoffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mEnabled && PetalConfig.areDepthClocksAligned(context)) startRise();
        }
    };
    private android.animation.ValueAnimator mRiseAnim;
    private float mRise;
    private float mRiseAlpha = 1f;

    private void startRise() {
        if (mRiseAnim != null) mRiseAnim.cancel();
        final float drop = PetalConfig.DEPTH_HANDOFF_DROP_DP * getResources().getDisplayMetrics().density;
        final long sink = PetalConfig.DEPTH_HANDOFF_SINK_MS, total = sink + PetalConfig.DEPTH_HANDOFF_RISE_MS;
        final float split = sink / (float) total;
        final android.view.animation.Interpolator down = new android.view.animation.AccelerateInterpolator(1.5f);
        final android.view.animation.Interpolator up = new android.view.animation.DecelerateInterpolator(2f);
        mRise = 0f;
        mRiseAlpha = 1f;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(total);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            mRise = f < split ? drop * down.getInterpolation(f / split)
                    : drop * (1f - up.getInterpolation((f - split) / (1f - split)));
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mRiseAnim != animation) return;
                mRiseAnim = null;
                mRise = 0f;
                mRiseAlpha = 1f;
                invalidate();
            }
        });
        mRiseAnim = anim;
        anim.start();
    }

    private boolean mEnabled;
    private Bitmap mSubject;
    private int mLoadedGeneration = Integer.MIN_VALUE;
    private int mLoadRequest;
    private int mStyle;
    private boolean mVertical;
    private float mAnchorX;
    private float mAnchorY;
    private float mScale;
    private int mInk;
    private boolean mShowDate;
    private String mHour = "";
    private String mMinute = "";
    private String mDate = "";

    public DepthHomeLayer(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setVisibility(GONE);
        // lock clock + studio run rubik, without this home fell back to plain sans = wider digits, round colon
        try {
            org.petalos.config.DepthClockMaterials.setBaseTypeface(
                    context.getResources().getFont(com.android.launcher3.R.font.petal_rubik));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (String key : KEYS) {
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    PetalConfig.depthKey(key, true)), false, mSettingsObserver);
        }
        // these two are home-only already, no twin
        for (String key : new String[] {PetalConfig.KEY_DEPTH_HOME,
                PetalConfig.KEY_DEPTH_HOME_WALLPAPER_ID}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mSettingsObserver);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        getContext().registerReceiver(mTimeReceiver, filter, Context.RECEIVER_EXPORTED);
        // only sysui (status bar perm) gets to kick this
        getContext().registerReceiver(mHandoffReceiver,
                new IntentFilter(PetalConfig.ACTION_DEPTH_CLOCK_HANDOFF),
                android.Manifest.permission.STATUS_BAR_SERVICE, null, Context.RECEIVER_EXPORTED);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        getContext().unregisterReceiver(mTimeReceiver);
        getContext().unregisterReceiver(mHandoffReceiver);
        if (mRiseAnim != null) mRiseAnim.cancel();
        mRiseAnim = null;
        mRise = 0f;
        mRiseAlpha = 1f;
        mLoadRequest++;
        super.onDetachedFromWindow();
    }

    private void refresh() {
        Context context = getContext();
        mStyle = PetalConfig.getDepthClockStyle(context, true);
        mVertical = PetalConfig.isDepthClockStackVertical(context, true);
        mAnchorX = PetalConfig.getDepthClockXPct(context, true) / 100f;
        mAnchorY = PetalConfig.getDepthClockAnchorPct(context, true) / 100f;
        mScale = PetalConfig.getDepthClockScalePct(context, true) / 100f;
        mInk = DepthClockRenderer.inkFor(mStyle, PetalConfig.getDepthClockColor(context, true));
        mShowDate = PetalConfig.isDepthClockDateEnabled(context, true);
        mClock.setColon(PetalConfig.isDepthClockColonEnabled(context, true));
        String uri = PetalConfig.getDepthCutoutUri(context, true);
        mEnabled = PetalConfig.isDepthHomeEnabled(context) && uri != null
                && mStyle != DepthClockRenderer.STYLE_OFF && homeWallpaperIsStudios();
        if (!mEnabled) {
            mLoadRequest++;
            mSubject = null;
            mLoadedGeneration = Integer.MIN_VALUE;
            setVisibility(GONE);
            return;
        }
        int generation = PetalConfig.getDepthGeneration(context, true);
        if (generation != mLoadedGeneration || mSubject == null) loadSubject(uri, generation);
        updateTime();
    }

    // The cutout was cut from the wallpaper Depth Studio set; on any other it would float.
    private boolean homeWallpaperIsStudios() {
        int expected = Settings.System.getInt(getContext().getContentResolver(),
                PetalConfig.KEY_DEPTH_HOME_WALLPAPER_ID, -1);
        try {
            return expected >= 0 && WallpaperManager.getInstance(getContext())
                    .getWallpaperId(WallpaperManager.FLAG_SYSTEM) == expected;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read the home wallpaper id", e);
            return false;
        }
    }

    private void loadSubject(String uri, int generation) {
        int request = ++mLoadRequest;
        Context context = getContext().getApplicationContext();
        UI_HELPER_EXECUTOR.execute(() -> {
            Bitmap bitmap = decode(context, uri);
            // home never loaded these, so frost/liquid fell back to the flat painted gradient
            // and looked nothing like the studio preview. same files studio saved for home
            Bitmap frost = decode(context, "content://org.petalos.depth/home_frost.png");
            Bitmap liquid = decode(context, "content://org.petalos.depth/home_liquid.png");
            Bitmap result = bitmap;
            MAIN_EXECUTOR.execute(() -> {
                if (request != mLoadRequest || !mEnabled) return;
                mClock.setBackdrop(frost);
                mClock.setLiquidBackdrop(liquid);
                mSubject = result;
                mLoadedGeneration = generation;
                setVisibility(result != null ? VISIBLE : GONE);
                invalidate();
            });
        });
    }

    private static Bitmap decode(Context context, String uri) {
        try (ParcelFileDescriptor fd = context.getContentResolver()
                .openFileDescriptor(Uri.parse(uri), "r")) {
            return fd == null ? null : BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
        } catch (Exception e) {
            Log.w(TAG, "Could not load " + uri, e);
            return null;
        }
    }

    private void updateTime() {
        mCalendar.setTimeZone(TimeZone.getDefault());
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        int hour;
        if (DateFormat.is24HourFormat(getContext())) {
            hour = mCalendar.get(Calendar.HOUR_OF_DAY);
        } else {
            hour = mCalendar.get(Calendar.HOUR);
            if (hour == 0) hour = 12;
        }
        mHour = String.format(Locale.US, "%02d", hour);
        mMinute = String.format(Locale.US, "%02d", mCalendar.get(Calendar.MINUTE));
        mDate = DateFormat.format(DATE_FORMAT, mCalendar).toString();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap subject = mSubject;
        if (!mEnabled || subject == null) return;
        // Both layers live in screen space, like the wallpaper they're cut from.
        getLocationOnScreen(mScreenPosition);
        // display size, same as the lock clock, so the two land on the same pixels
        android.graphics.Rect display = PetalConfig.displayBounds(getContext());
        int screenW = display.width();
        int screenH = display.height();
        canvas.save();
        canvas.translate(-mScreenPosition[0], -mScreenPosition[1]);
        if (mRiseAlpha > 0f) {
            int clockSave = canvas.save();
            canvas.translate(0f, mRise);
            if (mRiseAlpha < 1f) canvas.saveLayerAlpha(null, Math.round(255f * mRiseAlpha));
            mClock.draw(canvas, screenW, screenH, mStyle, mVertical, mAnchorX, mAnchorY, mScale,
                    mInk, mShowDate, mHour, mMinute, mDate);
            canvas.restoreToCount(clockSave);
        }
        mSubjectRect.set(0, 0, screenW, screenH);
        canvas.drawBitmap(subject, null, mSubjectRect, mSubjectPaint);
        canvas.restore();
    }
}
