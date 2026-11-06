# petalOS Atelier implementation notes

## Scope and evidence

This pass implements the visual foundation, cutout treatments and icon motion in the existing
Launcher3/Quickstep architecture. The three reference screenshots were not attached
to the conversation and were not found in the workspace material inspected. The
design review below follows the supplied descriptions, not an image analysis.
A device was unavailable during the audit, then a PLR110 connected over ADB.
An initial iteration was installed with `adb install -r` after verifying matching
petalOS signing certificates. Before/after screenshots and baseline frame statistics
were captured. The user then disconnected the phone and rejected that iteration's
dock and insufficient preset differences. The final dock revision described here
is built locally but has **not** been installed or visually accepted on the device.

Based on the brief, the useful iOS principle is consistent visual mass and hierarchy;
the useful ColorOS principle is deliberate relationships between different sizes.
For petalOS, the immediate priority is legible original artwork, coordinated matte
surfaces and restrained interaction. Expressive cell spans are a separate layout
feature, not a prerequisite for this material system.

## Architecture map and baseline

| Area | Existing implementation and finding |
| --- | --- |
| Application | `Android.bp`: ROM target `Launcher3QuickStep`; Java/View workspace with Quickstep integration. |
| Layout/data | `Workspace`, `CellLayout`, `DeviceProfile`, `ModelWriter`, existing launcher database. No span, grid, model or migration changes in this pass. |
| Icon loading | `icons/LauncherIconProvider.java` → shared `BaseIconFactory.kt` → icon cache. Native adaptive icons and dynamic clock/calendar support already exist. Legacy artwork previously used a fixed-scale white adaptive wrapper without measuring intrinsic padding. |
| Presentation | `BubbleTextView`, shared `FastBitmapDrawable`, `ThemeManager`, `ShapesProvider`; shared shapes already include squircle and pebble options. Notification dots and user badges use existing paths. |
| Custom artwork | `petalos/IconPackManager.java`, `SubjectIconStore.kt` and `graphics/theme/SubjectIconThemeFactory.kt`. Selected pack icons return before the new normalizer. Custom shortcut and subject-image generation are not routed through the new app-icon fitter. |
| Monochrome | `MonoIconThemeFactory.kt` used `shouldForceThemeIcon = true`, permitting inferred monochrome artwork when the feature flag is enabled. |
| Dock | `Hotseat.java` aligned a glass plate with actual icon bounds, but unconditionally created a background blur drawable and used accent1_500 at low opacity. |
| Folders | `PreviewBackground`, `Folder`, `FolderAnimationManager`, `FolderSpringAnimatorSet`; an existing `PetalFolderGlass` supplied a separate dark/glass material and prominent sheen. Spatial reveal and preview alignment already exist. |
| Labels | `DoubleShadowBubbleTextView` supplies ambient/key shadows. Existing contrast pills are conditional on Matcha/feature flags. |
| Wallpaper | `WallpaperColorHints` caches framework hints; `WallpaperThemeManager` handles activity-theme recreation. Monet system resources already supply tonal palettes. |
| Settings | `settings/SettingsActivity.java` and `res/xml/launcher_preferences.xml` provide a petalOS Home settings screen, persisted in `LauncherFiles.SHARED_PREFERENCES_KEY`. The tree also has `vendor/petal/apps/PetalHub`; these launcher-owned preferences use the existing Home screen rather than introducing cross-process duplicate settings. |
| SystemUI effects | `frameworks/base/packages/SystemUI/.../island/render/RefractionShader.kt` uses RuntimeShader, blur and cached RenderEffects. Its window/artwork refraction is unnecessary for these static launcher surfaces. |

## Implemented design tokens

The owning implementations are `petalos/AtelierSurface.java`,
`AtelierLabelProtection.java`, `AtelierIconNormalizer.java`, `AtelierMotion.java`
and `res/values/dimens.xml`. These form the initial reusable foundation.

| Role | Token/decision |
| --- | --- |
| Surface tone | Green-grey base (#252F2A / #EDF2EC), blended with 22% Monet neutral2 tone 800 / 50 when harmony is on. |
| Bloom accent | 24% of subdued accent2 tone 100 / 800 mixed into neutral; never the strongest wallpaper accent. |
| Material | Cached vertical tonal gradient, 0.5dp low-alpha edge, three bounded contact-shadow contours; no blur or refraction. |
| Surface opacity | Shelf: Atelier 246/255, Gallery absent, Bloom 232/255. Folders: 250/255 for text protection. |
| Geometry | Shelf radius 28% of height for Atelier, 48% for Bloom, bounded by half the shorter side. Existing folder reveal masks retained. |
| Shelf spacing | 12dp minimum screen-edge inset; requested vertical padding 12dp, constrained by existing Hotseat height. Actual icon/folder bounds determine the plate center. |
| Dock icon geometry | Atelier app artwork 84% of configured size; Bloom 92%; Gallery or surface disabled 100%. Logical cells and full touch targets retained. |
| Labels | 4dp horizontal backing inset, 5dp corner radius; local opposing black/white backing follows foreground alpha and actual laid-out lines. Existing shadows remain. |
| Typography | Existing DeviceProfile sizes, system typeface, font scaling, truncation and label-visibility preferences retained. |
| Legacy icon fit | Target alpha area 0.36 of mask area; extent cap 0.72; radial cap 0.44; enlargement capped at 1.6×. Alpha-centroid adjustment limited to 2.5% of sample size. |
| Icon raster | 96–384px, based on requested density, at icon load/cache generation. No bitmap analysis in drawing. |
| Elevation | Existing workspace icon shadow and folder elevation retained. Shelf contact separation uses static, bounded contours without an offscreen layer. |
| Press | Immediate 0.985 initial scale, settling to 0.965 over 70ms. |
| Recovery | Finite 220ms damped step response, exact rest endpoint, current-value retargeting. Not a velocity-conserving spring simulation. |

## Feature changes and their boundaries

### Petal Shelf and folders

`Hotseat.java` now uses `AtelierSurface`; `folder/PetalFolderGlass.java` is a small
compatibility adapter to that same material. The shelf no longer creates a blur
window. Folders no longer allocate blur listeners or draw the old multi-stop sheen.
Paints and bounds are reused; gradients are cached by palette and bounds.

The palette updates on attach, preference changes, wallpaper-hint changes and
configuration changes. Listeners are removed on detach. Shelf light/dark tone uses
the cached dark-text wallpaper hint, with a deterministic dark fallback when hints
are unavailable. Folder tone follows the actual folder text attribute, protecting
readability when the activity theme differs from the wallpaper.

Gallery removes the shelf surface and restores full-size floating app icons.
Atelier has an inset, almost opaque green-grey dock, less capsule-like corners and
compact app icons to make internal padding visible. Bloom has a more rounded shelf,
larger app icons, stronger secondary tint and a little more wallpaper visibility.
Open folders stay almost opaque in all presets.
These are explicitly **surface presets**, not promises of new layouts or animations.
The separate shelf-surface switch supports floating dock icons without hiding apps.
The older whole-dock toggle keeps its existing behavior.

`ShortcutAndWidgetContainer.java` applies dock-only artwork sizing during measurement;
`BubbleTextView` keeps its base size so moving an item back to the workspace restores
it. Drawable, drag, notification-dot and launch-source bounds use the new size;
hit-target view bounds and item order do not shrink. The material listener requests
layout on preset changes, so changes affect geometry as well as paint. Folder icons
keep their existing preview dimensions in the dock.

Compilation passed. Runtime theme changes, landscape/vertical dock presentation,
folder reveals and gesture-bar composition still need device visual checks. The
existing code does not draw a shelf plate for a vertical hotseat.

### Icon optical fitting and monochrome fallback

`icons/LauncherIconProvider.java` fits **legacy application icons** by calling
`AtelierIconNormalizer` after package loading. It measures alpha bounds, filled area
and centroid, preserves aspect ratio, and includes radial protection for diagonal
extremities. A neutral adaptive wrapper lets existing masks, cache rendering,
work-profile badges and notification dots continue to operate. Native adaptive
icons return unchanged; app artwork is not thresholded or recolored. The cache-state
suffix `atelier-optical-2` invalidates derived icons, not workspace records.
Mostly opaque legacy surfaces bypass fitting: the first device comparison showed
that wrapping one dark full-bleed icon introduced a white frame. That regression is
fixed, and the cache revision ensures the corrected artwork regenerates on update.

`graphics/theme/MonoIconThemeFactory.kt` disables forced monochrome synthesis.
Genuine monochrome layers, including existing curated layers, remain available;
unsupported apps fall back to original artwork through the existing theme pipeline.
The same cache-state revision also causes old synthesized icon entries to regenerate.

Native-Skia pixel tests cover adaptive-layer identity, empty artwork fallback,
restoring source bounds, comparable circle/square alpha mass with round overshoot,
transparent-padding compensation, full-bleed surface preservation, radial safety and
retained logo hue with allowance for low-alpha raster-edge rounding. Alpha mass
is a bounded optical heuristic: it cannot infer semantic logo importance or correct
intrinsic padding inside native adaptive foregrounds. Pack/custom artwork remains
under its existing pipeline. There are no new Nightfall or Natural style selectors.

### Local label protection

`BubbleTextView.java`, `views/DoubleShadowBubbleTextView.java` and
`AtelierLabelProtection.java` add optional backing only behind workspace labels.
The backing follows ellipsized/multiline layout and text visibility/alpha, excludes
the dock, and yields to existing Matcha contrast pills. The foreground's relative
luminance determines backing polarity. It neither samples nor dims wallpaper, and
therefore also works as a deterministic fallback for live wallpapers and faces.

Compilation passed; visual clipping, large fonts, RTL, accessibility high-contrast
text and all wallpaper categories still require device checks. This is uniform
local protection, not regional saliency detection or a claim of certified contrast
for arbitrary user-selected foreground colors.

### Tactile press feedback

`AtelierMotion.java` uses `FastBitmapDrawable`'s existing public scale property and
animator slot. `BubbleTextView.drawableStateChanged()` replaces press scaling only
for workspace and folder icons, after normal pressed-state propagation. Native
hover handling and other displays keep their original path. Drag preparation and
hidden icons use existing scale resets; visibility loss also cancels this feedback.
Launch code can continue to read `getAnimatedScale()` from the same channel.

ObjectAnimator honors system duration scaling. Disabled animators and the new
Reduce icon motion preference reset the scale without starting recovery. The
preference is checked at the next press-state transition. No idle animation is added.
Compilation passed; rapid taps, keyboard activation, pointer hover after a click,
cancel/drag handoff and app-launch continuity still need device interaction checks.
Folder and page transitions retain their existing implementations. The optional
Home icon entrance is updated as described below; app-window/remote transition
machinery remains in place.

### Wallpaper-aware cutouts and direct backdrop refraction

`petalos/SubjectIconStore.kt` adds a persisted `wallpaper` style key through the
existing cutout provider and theme identity. `graphics/theme/SubjectIconThemeFactory.kt`
renders this style using muted Monet neutral2/accent2 tones rather than each app's
background color. Gradient caching includes the resolved palette color, so a palette
change at the same icon size is not ignored. Original cutout artwork stays colored;
the existing monochrome switch optionally uses a contrast-oriented neutral foreground.

Wallpaper & style exposes **Wallpaper harmony** in its existing Cutout Icons style
selector. ThemePicker changes are in `res/layout/floating_sheet_app_icon.xml`,
`res/values/subject_icon_strings.xml` and `AppIconFloatingSheetBinder.kt`. Selection
and restoring the selected chip use the existing provider/repository path; no new
model-inference batch is needed to change styles for already generated cutouts.

Refraction previously blurred and refracted `info.icon` over `info.color` into a
RenderNode or bitmap. It now uses `graphics/theme/SubjectIconBackdrop.java` and the
ROM's `BackgroundBlurDrawable.setRefraction()` compositor path. The drawable tint
is transparent; the app's original bitmap, flat background, shadow plate and added
rim are omitted. The colored cutout draws above the actual window backdrop, which
is the wallpaper on Home. No wallpaper bitmap is captured or retained, including
for live wallpapers.

Each backdrop is associated with the actual drawable callback View. Bounds follow
the framework RenderNode position listener through transformations. Detach, drawable
replacement, visibility loss and blur-disabled callbacks hide/release the region and
unregister listeners. Software snapshots, filtered/faded drawables and unsupported
blur fall back to a transparent cutout, not the old icon-texture lens. Refraction is
an explicitly selected graphics effect: it adds compositor work per visible icon
and needs device profiling. The compositor's rounded-rectangle contour approximates
noncircular custom icon masks; custom shape edges need visual validation.

Both Launcher and ThemePicker compile. The final styles have not been visually
tested on the disconnected phone; cross-window blur, live wallpaper, app drawer,
preview/snapshot fallback, custom masks and fast transition interruption remain
runtime acceptance items.

### Faster, staggered Home return

`anim/PetalHomeReturnAnimation.java` supplies the shared workspace-item cascade.
The icon entrance duration is 270ms instead of the previous 350ms. Delay uses the
actual grid row and visual column (8ms per row, 5ms per column, 64ms maximum), so
icons in the same row no longer all receive the same capped pixel-based delay.
RTL reverses the visual column ordering. Existing motion distance/scale treatment
is retained; each item's original transforms are restored on completion/cancellation.

`WorkspaceRevealAnim.java` uses this helper for app/recents returns and no longer
sets a parent duration that would override the shorter child durations. The outer
workspace, dock, depth and scrim timing remains 350ms. Dock children retain their
existing container-driven return animation to avoid clipping them outside the shelf.

`TouchInteractionService.java` forwards existing SystemUI shade-state changes to
`QuickstepLauncher.onPetalSystemUiFlagsChanged()`. Closing QS/notification shade over
an unlocked, resumed Home uses the same cascade. It does not use generic focus loss
as a proxy for QS. A new touch, shade reopening, another launcher state, pause or
destruction cancels the shade reveal. The existing Return to Home toggle gates the
effect; Reduce icon motion and system-disabled animations suppress it. No new
SystemUI broadcast or continuous animation is added.

Compilation passed. App/recents/QS timing and interruption behavior have not been
interaction-tested on the final build after disconnection.

### Persistence and controls

`PetalLauncherPrefs.java`, `res/xml/launcher_preferences.xml` and
`res/values/petal_atelier.xml` add the following under Home settings → petalOS:

- Surface preset: Atelier / Gallery / Bloom.
- Wallpaper harmony: dynamic muted system colors or a fixed green-grey material.
- Protect Home labels: readability backing or existing shadow-only presentation.
- Petal Shelf surface: matte shelf or floating icons.
- Reduce icon motion: disable Atelier press/recovery scaling.

Preference listeners invalidate attached surfaces and labels; detached views refresh
when attached again. Motion reads its preference on input. No launcher reset,
database schema change, widget modification, global icon-mask override or new
third-party dependency is introduced. The initial settings activity was launched
on-device; final preset geometry and persistence across process restart have not
been device-tested.

## Engineering references and technique choices

- [Material Color Utilities](https://github.com/material-foundation/material-color-utilities)
  describes HCT as CAM16 hue/chroma with L* tone. Reuse the platform's Monet neutral
  ramp rather than extracting the most saturated wallpaper color or implementing
  a second HCT/OKLCH conversion pipeline. The small surface blends are ordinary
  ARGB compositing, not a claim of perceptually uniform interpolation.
- Local `frameworks/base/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java`
  and shared `frameworks/libs/systemui/iconloaderlib/.../BaseIconFactory.kt` establish
  layer inset/masking behavior. The fitter compensates for the adaptive layer's
  extra inset and leaves real adaptive assets intact. See also
  [adaptive icons](https://source.android.com/docs/core/display/adaptive-icons).
- Local `WallpaperColorHints.kt` and framework `WallpaperColors` provide the cached
  hint lifecycle; no full-resolution wallpaper access, continuous analysis, or
  assumptions about live-wallpaper frames are needed. See
  [WallpaperColors](https://developer.android.com/reference/android/app/WallpaperColors).
- Existing folder reveal/spring code provides spatial continuity. A damped finite
  interpolation for the smaller press response shares the existing drawable scale
  channel and cancellation machinery rather than adding another view-scale owner.
- Island refraction was inspected. A cached Canvas gradient and low-alpha edge
  suffice for this material; adding AGSL, grain, sampling blur or offscreen effects
  would add work without an established need. See Android's
  [window blur constraints](https://source.android.com/docs/core/display/window-blurs).

The adaptive-icons and WallpaperColors web requests timed out during the audit;
their checked-out implementations were available for source review. The Material
Color Utilities repository documentation was successfully retrieved.

## Build and test results — 2026-10-25

The supported configuration used for the final builds was the user's requested:

```sh
source build/envsetup.sh
lunch petal_macanc-bp4a-user
m Launcher3QuickStep ThemePicker PetalAtelierRoboTests -j12
```

Result: **passed**, including Java/Kotlin compilation, resource linking, R8, APK
signing and launcher dexpreopt. Output:

`out/target/product/macanc/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk`

`out/target/product/macanc/system_ext/priv-app/ThemePicker/ThemePicker.apk`

The standalone optical test artifact compiled. Seven tests were executed through
JUnitCore with native graphics on the checked-in **upstream Android 16/API 36**
Robolectric runtime: **7 passed**, 7.993 seconds. These cover the normalizer, not the
new compositor backdrop or Home return interactions. This is not a test on the ROM device.
`tests/atelier/upstream-sdk.properties` records the dependency mapping used to avoid
the ROM-built host runtime's missing `/system/framework/org.lineageos.platform-res.apk`.
The final successful run used that checked-in relative-path mapping.

For direct host execution, use working directory
`out/host/linux-x86/testcases/PetalAtelierRoboTests` and the tree's JDK 21 `java`, with:

- `--add-opens=java.base/java.lang=ALL-UNNAMED`
- `--add-opens=java.base/java.lang.reflect=ALL-UNNAMED`
- `-Drobolectric-deps.properties=<tree>/packages/apps/Launcher3/tests/atelier/upstream-sdk.properties`
- `-Drobolectric.offline=true`
- Classpath entries: that directory's `PetalAtelierRoboTests.jar`,
  `out/soong/.intermediates/packages/apps/Launcher3/tests/PetalAtelierTestApp/android_common/javac/PetalAtelierTestApp.jar`,
  and `prebuilts/misc/common/robolectric/android-all/android-all-16-robolectric-13921718.jar`.
- Main class/argument: `org.junit.runner.JUnitCore com.android.launcher3.petalos.AtelierIconNormalizerTest`.

Other verification limits encountered:

- `Launcher3RoboTests` could not build because its non-Quickstep `Launcher3`
  dependency cannot resolve the existing `SettingsBasePreferenceFragment` import.
  The isolated test target avoids that unrelated dependency.
- This checkout's atest module lookup did not discover the newly added module;
  tests were run directly instead. The attempted `m module-info.json` target was
  unavailable. No full-suite pass is claimed.
- `git diff --check` passed during review.
- Initial deployment succeeded and retained the visible Home arrangement,
  wallpaper, hidden labels and notification dots. The rejected initial screenshots
  are `/tmp/opencode/atelier-before.png` and `/tmp/opencode/atelier-after.png`.
  They are not evidence of the final dock revision.
- A baseline `dumpsys gfxinfo` sample after reset and five drawer-open/Home-return
  cycles recorded 975 frames, 97 janky frames (9.95%), P50 10ms, P90 22ms, P95 26ms,
  P99 34ms; GPU P50 5ms/P95 15ms. Raw data:
  `/tmp/opencode/atelier-baseline-gfx.txt`. This is one exploratory sample, with
  no thermal control or final-build comparison. No speedup/non-regression claim
  follows from it.
- Final revision deployment/visual acceptance, accessibility service testing,
  widget/work-profile interaction matrix, comparative frame profiling and battery
  testing remain outstanding after the phone was disconnected.

## Remaining work, ranked

1. **Device visual and interaction acceptance — highest value, low architectural cost.**
   Check the supplied difficult wallpaper plus black, white, faces, landscapes,
   abstract gradients and live wallpaper; include all presets, font scaling/RTL,
   rotation, reduced motion, drag cancellation, badges and launch/return transitions.
2. **Reproducible graphics profiling — high value, requires a device.** Compare
   baseline/current with identical wallpaper, layout, refresh rate and thermal state.
   Measure idle, page swipes, drawer scrolling, cold/warm icon loading, folder reveals
   and interrupted app launches using Perfetto and frame timing. No performance or
   battery non-regression claim is justified by the build or pixel tests alone.
3. **More precise label treatment — medium cost.** After visual evaluation, consider
   cached regional luminance only if it can reduce unnecessary backing without live
   wallpaper frame access or per-scroll analysis.
4. **Native monochrome tonal/Nightfall options — medium cost.** Add distinctly useful
   palettes through ThemeManager with proper cache identities, testing dynamic clocks,
   unsupported artwork and icon-pack precedence before exposing another style.
5. **Petal Tiles — high cost.** Start with supported pinned shortcuts or existing
   widgets, then design reversible cell spans. Prove persistence, grid migration,
   multi-user handling, reorder transactions, accessibility and no-overlap behavior
   before adding tile selectors. No experimental reflow code is included here.
