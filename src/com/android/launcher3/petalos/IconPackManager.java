/*
 * Copyright (C) 2026 The petalOS Project
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

package com.android.launcher3.petalos;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.graphics.ThemeManager;

import org.petalos.config.PetalConfig;
import org.xmlpull.v1.XmlPullParser;

import java.util.Map;

/**
 * Applies a Candybar-style icon pack to the app drawer and home screen.
 *
 * <p>Reads the selected pack from {@link PetalConfig#KEY_ICON_PACK}, parses the pack's
 * {@code appfilter.xml} (which maps component names to drawable names) and resolves the matching
 * drawable from the pack's resources. When the selection changes, the icon cache and workspace are
 * invalidated so the new icons appear immediately.
 */
public class IconPackManager {

    private static final String TAG = "PetalIconPack";
    private static final String TAG_ITEM = "item";
    private static final String ATTR_COMPONENT = "component";
    private static final String ATTR_DRAWABLE = "drawable";
    private static final String COMPONENT_INFO_PREFIX = "ComponentInfo{";

    private static IconPackManager sInstance;

    private final Context mAppContext;
    private final PackageManager mPm;
    private final Map<ComponentName, String> mIconMap = new ArrayMap<>();
    private String mLoadedPack = "";

    private final ContentObserver mObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    reload();
                }
            };

    private IconPackManager(Context context) {
        mAppContext = context.getApplicationContext();
        mPm = mAppContext.getPackageManager();
        mAppContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(PetalConfig.KEY_ICON_PACK), false, mObserver);
    }

    public static IconPackManager get(Context context) {
        if (sInstance == null) {
            sInstance = new IconPackManager(context);
        }
        return sInstance;
    }

    /** Returns the pack's replacement icon for {@code component}, or null when unmapped. */
    @Nullable
    public Drawable getIcon(ComponentName component, int density) {
        ensureLoaded();
        String drawableName = mIconMap.get(component);
        if (drawableName == null || mLoadedPack.isEmpty()) {
            return null;
        }
        Drawable drawable = loadDrawable(mLoadedPack, drawableName, density);
        if (drawable != null) {
            Log.d(TAG, "pack icon " + drawableName + " for " + component);
        }
        return drawable;
    }

    /** Returns the pack's icon for a package's launcher activity, or null when unmapped. */
    @Nullable
    public Drawable getIconForPackage(String packageName, int density) {
        ensureLoaded();
        if (mLoadedPack.isEmpty() || packageName == null) {
            return null;
        }
        // Most packs key by the launcher activity; resolve it from the launch intent.
        ComponentName launcher = mPm.getLaunchIntentForPackage(packageName) != null
                ? mPm.getLaunchIntentForPackage(packageName).getComponent()
                : null;
        return launcher != null ? getIcon(launcher, density) : null;
    }

    /** A token identifying the current pack, folded into the icon cache key. */
    public String getCacheKey() {
        String pack = PetalConfig.getIconPack(mAppContext);
        if (pack == null || pack.isEmpty()) {
            return "petal:none";
        }
        return "petal:" + pack;
    }

    /** True when a non-default icon pack is selected (themed icons must be suppressed). */
    public boolean isPackActive() {
        String pack = PetalConfig.getIconPack(mAppContext);
        return pack != null && !pack.isEmpty();
    }

    private void reload() {
        mLoadedPack = "";
        mIconMap.clear();
        ensureLoaded();

        try {
            LauncherAppState state = LauncherAppState.getInstance(mAppContext);
            // petalOS: themed icons are disabled while a pack is selected and re-enabled for the
            // system default pack, so re-evaluate the theme state first — its unique id feeds the
            // icon cache key via LauncherIconProvider#updateSystemState.
            ThemeManager.INSTANCE.get(mAppContext).onIconPackChanged();
            // Drop any in-memory icon entries so nothing stale survives the pack switch
            // (bug: selecting "system" kept showing the previously-applied pack's icons).
            state.getIconCache().workerHandler.post(state.getIconCache()::clearMemoryCache);
            state.getIconProvider().updateSystemState();
            state.getModel().forceReload();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to refresh launcher after icon pack change", e);
        }
    }

    private void ensureLoaded() {
        String pack = PetalConfig.getIconPack(mAppContext);
        if (pack == null) {
            pack = "";
        }
        if (pack.equals(mLoadedPack)) {
            return;
        }
        mLoadedPack = pack;
        mIconMap.clear();
        if (!pack.isEmpty()) {
            loadAppFilter(pack);
            Log.d(TAG, "loaded icon pack " + pack + " with " + mIconMap.size() + " mappings");
        }
    }

    private void loadAppFilter(String pack) {
        try {
            Resources res = mPm.getResourcesForApplication(pack);
            int xmlId = res.getIdentifier("appfilter", "xml", pack);
            if (xmlId == 0) {
                Log.w(TAG, "no appfilter.xml found in " + pack);
                return;
            }
            try (XmlResourceParser parser = res.getXml(xmlId)) {
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG || !TAG_ITEM.equals(parser.getName())) {
                        continue;
                    }
                    String component = parser.getAttributeValue(null, ATTR_COMPONENT);
                    String drawable = parser.getAttributeValue(null, ATTR_DRAWABLE);
                    ComponentName cn = parseComponentName(component);
                    if (cn != null && drawable != null) {
                        mIconMap.put(cn, drawable);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to parse appfilter for " + pack, e);
        }
    }

    private ComponentName parseComponentName(String component) {
        if (component == null || component.isEmpty()) {
            return null;
        }
        if (component.startsWith(COMPONENT_INFO_PREFIX) && component.endsWith("}")) {
            component = component.substring(
                    COMPONENT_INFO_PREFIX.length(), component.length() - 1);
        }
        return ComponentName.unflattenFromString(component);
    }

    private Drawable loadDrawable(String pack, String name, int density) {
        try {
            Resources res = mPm.getResourcesForApplication(pack);
            int id = res.getIdentifier(name, "drawable", pack);
            if (id == 0) {
                return null;
            }
            return res.getDrawableForDensity(id, density, mAppContext.getTheme());
        } catch (Exception e) {
            Log.w(TAG, "Unable to load icon " + name + " from " + pack, e);
            return null;
        }
    }
}
