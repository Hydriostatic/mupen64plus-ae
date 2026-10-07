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
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shows an expansion's (.exp) panel on the device's second screen (e.g. the AYN Thor's bottom
 * screen) while a game runs. The game keeps the main screen and the controller: the panel's
 * window never takes key focus, it only receives touches.
 */
final class SecondScreenPanel extends Presentation
{
    private static final String TAG = "SecondScreenPanel";

    private final Expansion mExpansion;
    private final Runnable mOpenMenu, mSaveAndQuit;

    private SecondScreenPanel(@NonNull Context outer, @NonNull Display display, @NonNull Expansion expansion,
                              Runnable openMenu, Runnable saveAndQuit)
    {
        super(outer, display);
        mExpansion = expansion;
        mOpenMenu = openMenu;
        mSaveAndQuit = saveAndQuit;
    }

    /** The second screen: the first usable display other than the main one (built-in panels first). */
    @Nullable
    static Display findSecondDisplay(@NonNull Context context)
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

    /** Show the expansion on the second screen. Returns null when there's no second screen. */
    @Nullable
    static SecondScreenPanel show(@NonNull Activity activity, @NonNull Expansion expansion,
                                  Runnable openMenu, Runnable saveAndQuit)
    {
        Display display = findSecondDisplay(activity);
        if (display == null) return null;
        try {
            SecondScreenPanel panel = new SecondScreenPanel(activity, display, expansion, openMenu, saveAndQuit);
            panel.show();
            return panel;
        } catch (WindowManager.InvalidDisplayException | IllegalStateException e) {
            Log.w(TAG, "Couldn't show the expansion on the second screen", e);
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        if (w != null) {
            // Touch only: keys and the gamepad stay with the game on the main screen
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setCancelable(false);
        setContentView(new ExpansionView(getContext(), mExpansion, mOpenMenu, mSaveAndQuit));
    }
}
