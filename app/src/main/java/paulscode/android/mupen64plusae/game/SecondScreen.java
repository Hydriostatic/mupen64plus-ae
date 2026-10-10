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
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the second screen in step with the main one: while any screen of this app is visible on
 * the main screen, the second screen is grey (the game screen shows its expansion there itself,
 * see GameActivity). It goes away exactly when the main screen's activity does: Back out of the
 * app, Home, switching apps, the screen turning off. Coming back brings it back.
 */
public final class SecondScreen implements Application.ActivityLifecycleCallbacks
{
    private static final String TAG = "SecondScreen";

    /** One grey panel per visible activity (normally one; two for a moment while switching). */
    private final Map<Activity, SecondScreenPanel> mPanels = new HashMap<>();

    private SecondScreen() {}

    /** Call once from Application.onCreate. */
    public static void install(@NonNull Application app)
    {
        app.registerActivityLifecycleCallbacks(new SecondScreen());
    }

    /** Activities that handle the second screen themselves, or never really show up. */
    private static boolean handlesItself(Activity a)
    {
        return a instanceof GameActivity || a instanceof ExpansionImportActivity;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity)
    {
        if (handlesItself(activity) || activity.isFinishing() || mPanels.containsKey(activity)) return;
        SecondScreenPanel panel = SecondScreenPanel.show(activity, null, null, null);
        if (panel != null) mPanels.put(activity, panel);
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity)
    {
        dismiss(activity);
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity)
    {
        dismiss(activity);
    }

    private void dismiss(Activity activity)
    {
        SecondScreenPanel panel = mPanels.remove(activity);
        if (panel == null) return;
        try {
            panel.dismiss();
        } catch (Exception e) {
            Log.w(TAG, "Couldn't close the second screen", e);
        }
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
    @Override public void onActivityResumed(@NonNull Activity a) {}
    @Override public void onActivityPaused(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
}
