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
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

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

    /**
     * True while a system screen we opened on purpose (the file picker for importing an
     * expansion) covers the second screen: that isn't the user leaving the app, so both screens
     * must stay where they are.
     */
    public static volatile boolean sSystemPickerOpen = false;

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

    private static boolean isMenuTask(ActivityManager.RecentTaskInfo info)
    {
        if (info.baseActivity == null) return false;
        // Both the in-game screen and the app menus' screen (SecondScreenAppMenuActivity, whose
        // name does NOT start with SecondScreenMenuActivity's)
        String name = info.baseActivity.getClassName();
        return name.equals(SecondScreenMenuActivity.class.getName()) ||
                name.equals(SecondScreenAppMenuActivity.class.getName());
    }

    /**
     * Is any screen of the app (other than the second-screen menu itself) still showing?
     * Returns null when this can't be told (Android 11 and older).
     */
    @Nullable
    public static Boolean isAppVisible(@NonNull Context context)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return null;
        try {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || isMenuTask(info)) continue;
                if (info.isVisible()) return true;
            }
            return false;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Is the second-screen menu (task {@code menuTaskId}) hidden by something that isn't part of
     * the app (e.g. the system home screen)? Null when this can't be told (Android 11 and older).
     */
    @Nullable
    public static Boolean isMenuCoveredByOtherApp(@NonNull Context context, int menuTaskId)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return null;
        try {
            boolean menuVisible = false, otherMenuVisible = false;
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || !isMenuTask(info)) continue;
                if (info.taskId == menuTaskId) menuVisible = info.isVisible();
                else if (info.isVisible()) otherMenuVisible = true; // e.g. the game's second screen
            }
            return !menuVisible && !otherMenuVisible;
        } catch (Exception e) {
            return null;
        }
    }

    /** Bring one of the app's tasks to the front (gives its screen the input focus). */
    public static void bringTaskToFront(@NonNull Context context, int taskId)
    {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return;
        try {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.taskId == taskId) task.moveToFront();
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't bring task to front", e);
        }
    }

    /** Bring the second-screen menu's task back in front on its screen, if it got covered. */
    public static void bringMenuToFront(@NonNull Context context, int menuTaskId)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return;
        try {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.taskId == menuTaskId && !info.isVisible()) {
                    Log.i(TAG, "Second-screen menu was covered; bringing it back");
                    task.moveToFront();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't bring the second-screen menu back", e);
        }
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
        // Open it in the second screen's own task, on top of the menu: Back then always returns
        // to the menu there, never to an empty screen
        SecondScreenMenuActivity menu = SecondScreenMenuActivity.current();
        if (menu != null && isEnabled(context)) {
            try {
                menu.startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Couldn't open page from the second-screen menu", e);
            }
        }

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
