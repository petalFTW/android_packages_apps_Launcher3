package com.android.launcher3.folder;

public class ClippedFolderIconLayoutRule {
    public static final int MAX_NUM_ITEMS_IN_PREVIEW = 9;
    public static final float MIN_SCALE = .26f;
    public static final float MAX_SCALE = MIN_SCALE;
    public static final float ICON_OVERLAP_FACTOR = 1f;
    public static final int EXIT_INDEX = -2;
    public static final int ENTER_INDEX = -3;
    private float mAvailableSpace;
    private float mIconSize;
    private boolean mIsRtl;
    private float mBaselineIconScale;

    public void init(int availableSpace, float intrinsicIconSize, boolean rtl,
            int numFolderColumns) {
        mAvailableSpace = availableSpace;
        mIconSize = intrinsicIconSize;
        mIsRtl = rtl;
        mBaselineIconScale = availableSpace / intrinsicIconSize;
    }

    public PreviewItemDrawingParams computePreviewItemDrawingParams(int index, int curNumItems,
            PreviewItemDrawingParams params) {
        int row = index == ENTER_INDEX ? 1 : index == EXIT_INDEX ? 0 : index / 3;
        int col = index < 0 ? 3 : index % 3;
        if (index >= MAX_NUM_ITEMS_IN_PREVIEW) {
            float center = (mAvailableSpace - mIconSize * scaleForItem(curNumItems, 0)) / 2f;
            return update(params, center, center);
        }
        return grid(params, row, col);
    }

    public PreviewItemDrawingParams computeSpringAnimationItemParams(int index, int numItemsInPage,
            int page, PreviewItemDrawingParams params) {
        return grid(params, index / 3, index % 3);
    }

    private PreviewItemDrawingParams grid(PreviewItemDrawingParams params, int row, int col) {
        // no bloody circular shuffle, just reading order
        if (mIsRtl) col = 2 - col;
        return update(params, mAvailableSpace * (.07f + col * .30f),
                mAvailableSpace * (.07f + row * .30f));
    }

    private PreviewItemDrawingParams update(PreviewItemDrawingParams params, float x, float y) {
        float scale = scaleForItem(9, 0);
        if (params == null) return new PreviewItemDrawingParams(x, y, scale);
        params.update(x, y, scale);
        return params;
    }

    public float scaleForItem(int numItems, int page) { return MIN_SCALE * mBaselineIconScale; }
    public float getIconSize() { return mIconSize; }
}
