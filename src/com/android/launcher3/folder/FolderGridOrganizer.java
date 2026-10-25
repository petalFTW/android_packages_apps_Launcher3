/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.launcher3.folder;

import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.MAX_NUM_ITEMS_IN_PREVIEW;

import android.graphics.Point;
import android.util.Log;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for managing item positions in a folder based on rank
 */
public class FolderGridOrganizer {

    private final Point mPoint = new Point();
    private final int mMaxCountX;
    private final int mMaxCountY;
    private final int mMaxItemsPerPage;

    private int mNumItemsInFolder;
    private int mCountX;
    private int mCountY;
    private boolean mDisplayingUpperLeftQuadrant = false;
    private static final int PREVIEW_MAX_ROWS = 3;
    private static final int PREVIEW_MAX_COLUMNS = 3;

    /**
     * Note: must call {@link #setFolderInfo(FolderInfo)} manually for verifier to work.
     */
    public FolderGridOrganizer(int maxCountX, int maxCountY) {
        mMaxCountX = maxCountX;
        mMaxCountY = maxCountY;
        mMaxItemsPerPage = mMaxCountX * mMaxCountY;
        mCountX = mMaxCountX;
        mCountY = mMaxCountY;
    }

    /**
     * Creates a FolderGridOrganizer for the given DeviceProfile
     */
    public static FolderGridOrganizer createFolderGridOrganizer(DeviceProfile profile) {
        return new FolderGridOrganizer(
                profile.getFolderProfile().getNumColumns(),
                profile.getFolderProfile().getNumRows()
        );
    }

    /**
     * Updates the organizer with the provided folder info
     */
    public FolderGridOrganizer setFolderInfo(FolderInfo info) {
        return setContentSize(info.getContents().size());
    }

    /**
     * Updates the organizer to reflect the content size
     */
    public FolderGridOrganizer setContentSize(int contentSize) {
        if (contentSize != mNumItemsInFolder) {
            calculateGridSize(contentSize);

            mDisplayingUpperLeftQuadrant = contentSize > MAX_NUM_ITEMS_IN_PREVIEW;
            mNumItemsInFolder = contentSize;
        }
        return this;
    }

    public int getCountX() {
        return mCountX;
    }

    public int getCountY() {
        return mCountY;
    }

    public int getMaxItemsPerPage() {
        return mMaxItemsPerPage;
    }

    // fixed pages, stop resizing this thing for two apps
    private void calculateGridSize(int count) {
        // empty slots stay put, the folder doesn't dance around anymore
        mCountX = mMaxCountX;
        mCountY = mMaxCountY;
    }

    // true means the saved position needs a kick
    public boolean updateRankAndPos(ItemInfo item, int rank) {
        Point pos = getPosForRank(rank);
        if (rank != item.rank || item.cellX != pos.x || item.cellY != pos.y) {
            item.rank = rank;
            item.cellX = pos.x;
            item.cellY = pos.y;
            return true;
        }
        return false;
    }

    /**
     * Returns the position of the item in the grid
     */
    public Point getPosForRank(int rank) {
        int pagePos = rank % mMaxItemsPerPage;
        if (mCountX == 0) {
            mPoint.x = 0;
            mPoint.y = 0;
        } else {
            mPoint.x = pagePos % mCountX;
            mPoint.y = pagePos / mCountX;
        }
        return mPoint;
    }

    /**
     * Returns the preview items for the provided pageNo using the full list of contents
     */
    public <T, R extends T> ArrayList<R> previewItemsForPage(int page, List<T> contents) {
        ArrayList<R> result = new ArrayList<>();
        int itemsPerPage = mCountX * mCountY;
        int start = itemsPerPage * page;
        int end = Math.min(start + itemsPerPage, contents.size());

        for (int i = start, rank = 0; i < end; i++, rank++) {
            if (isItemInPreview(page, rank)) {
                result.add((R) contents.get(i));
            }

            if (result.size() == MAX_NUM_ITEMS_IN_PREVIEW) {
                break;
            }
        }

        if (result.isEmpty()) {
            // Log specifics since we are getting empty result
            Log.d("b/383526431", "previewItemsForPage: "
                    + "mCountX = " + mCountX
                    + ", mCountY = " + mCountY
                    + ", content size = " + contents.size());
        }
        return result;
    }

    /**
     * Returns whether the item with rank is in the default Folder icon preview.
     */
    public boolean isItemInPreview(int rank) {
        return isItemInPreview(0, rank);
    }

    // the first nine slots are the preview now
    public boolean isItemInPreview(int page, int rank) {
        // page-local rows and columns, not the old four-icon corner
        if (page > 0 || mDisplayingUpperLeftQuadrant) {
            int col = rank % mCountX;
            int row = rank / mCountX;
            return col < PREVIEW_MAX_COLUMNS && row < PREVIEW_MAX_ROWS;
        }
        // small folders still use the same nine slots
        return rank < MAX_NUM_ITEMS_IN_PREVIEW;
    }
}
