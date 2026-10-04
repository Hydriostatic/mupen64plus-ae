/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae.game;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dual-screen support (e.g. AYN Thor): the main display shows the game list and the game, while
 * every menu and settings page goes on the second display.
 */
public final class SecondScreen
{
    private static final String TAG = "SecondScreen";

    /** Preference key of "Show menus on second screen" (Settings > Input). */
    public static final String PREF_KEY = "inGameMenuSecondScreen";

    private SecondScreen() {}

    // ---------------------------------------------------------------------------------------------
    // Which of this process's screens are showing, so the second screen is never left empty
    // ---------------------------------------------------------------------------------------------

    private static boolean sTracking = false;
    /** Started activities of this process, except the second-screen menu hosts. */
    private static final Set<Activity> sStartedPages = new HashSet<>();
    private static final List<Runnable> sPageStoppedListeners = new ArrayList<>();

    /** Start keeping track of this process's visible screens. Safe to call more than once. */
    static void track(@NonNull Context context)
    {
        if (sTracking) return;
        Application app = (Application) context.getApplicationContext();
        if (app == null) return;
        sTracking = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityResumed(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}

            @Override public void onActivityStarted(@NonNull Activity a)
            {
                if (!(a instanceof SecondScreenMenuActivity)) sStartedPages.add(a);
            }

            @Override public void onActivityStopped(@NonNull Activity a)
            {
                if (sStartedPages.remove(a) || a instanceof SecondScreenMenuActivity) notifyStopped();
            }

            @Override public void onActivityDestroyed(@NonNull Activity a)
            {
                if (sStartedPages.remove(a)) notifyStopped();
            }
        });
    }

    private static void notifyStopped()
    {
        for (Runnable r : new ArrayList<>(sPageStoppedListeners)) r.run();
    }

    static void addPageStoppedListener(@NonNull Runnable listener)
    {
        if (!sPageStoppedListeners.contains(listener)) sPageStoppedListeners.add(listener);
    }

    static void removePageStoppedListener(@NonNull Runnable listener)
    {
        sPageStoppedListeners.remove(listener);
    }

    /** True if one of this process's pages (not the menu host) is showing on that display. */
    static boolean isPageShownOn(int displayId)
    {
        for (Activity a : sStartedPages) {
            if (!a.isFinishing() && displayOf(a) == displayId) return true;
        }
        return false;
    }

    /** True if any page of this process (other than the menu hosts) is showing anywhere. */
    static boolean isAnyPageShown()
    {
        for (Activity a : sStartedPages) {
            if (!a.isFinishing()) return true;
        }
        return false;
    }

    public static boolean isEnabled(@NonNull Context context)
    {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true);
    }

    /**
     * The display the menus go on: the first usable display other than the main one, preferring
     * built-in panels (like the Thor's bottom screen) over HDMI/cast presentation displays.
     */
    @Nullable
    public static Display findMenuDisplay(@NonNull Context context)
    {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;

        Display fallback = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || !d.isValid()) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            if ((d.getFlags() & Display.FLAG_PRESENTATION) == 0) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    /** The display an activity is currently shown on. */
    @SuppressWarnings("deprecation")
    public static int displayOf(@NonNull Activity activity)
    {
        return activity.getWindowManager().getDefaultDisplay().getDisplayId();
    }

    /**
     * Start a menu/settings page. On dual-screen devices it opens on the second screen so the
     * main screen keeps showing the game list; otherwise it's a normal startActivity.
     *
     * Pages started from a page that is already on the second screen stay in that page's task,
     * so they open there too.
     */
    public static void startActivity(@NonNull Context context, @NonNull Intent intent)
    {
        if (context instanceof Activity && isEnabled(context)) {
            Activity activity = (Activity) context;
            Display target = findMenuDisplay(activity);

            if (target != null && displayOf(activity) != target.getDisplayId()) {
                Intent onSecond = new Intent(intent);
                // A separate task, so the game list's task stays on the main screen
                onSecond.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(target.getDisplayId());
                try {
                    activity.startActivity(onSecond, options.toBundle());
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Couldn't open page on display " + target.getDisplayId(), e);
                }
            }
        }
        context.startActivity(intent);
    }
}
