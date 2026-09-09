package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;

import com.android.launcher3.dagger.ApplicationContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    private final Set<ComponentName> mFilteredComponents;

    // petalOS: Depth Studio lives in Petal Hub, keep it out of the drawer
    private static final ComponentName DEPTH_STUDIO =
            ComponentName.unflattenFromString("org.petalos.depth/.DepthStudioActivity");

    @Inject
    public AppFilter(@ApplicationContext Context context) {
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
    }

    public boolean shouldShowApp(ComponentName app) {
        if (DEPTH_STUDIO.equals(app)) {
            return false;
        }
        return !mFilteredComponents.contains(app);
    }
}
