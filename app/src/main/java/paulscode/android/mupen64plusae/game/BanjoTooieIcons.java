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

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Icons for the Banjo-Tooie stats panel, by key ("jiggy", "note", "egg_blue", ...).
 *
 * Icons come from the player's own ROM at run time (nothing from the game is shipped with the
 * app). Until they are loaded, or if loading fails, {@link #get} returns null and the panel
 * draws a plain placeholder instead.
 */
public final class BanjoTooieIcons
{
    public static final String JIGGY = "jiggy", NOTE = "note", JINJO = "jinjo", PAGE = "page",
            HONEYCOMB = "honeycomb", GLOWBO = "glowbo", DOUBLOON = "doubloon", HEALTH = "health",
            EGG_BLUE = "egg_blue", EGG_FIRE = "egg_fire", EGG_GRENADE = "egg_grenade",
            EGG_ICE = "egg_ice", EGG_CLOCKWORK = "egg_clockwork",
            FEATHER_RED = "feather_red", FEATHER_GOLD = "feather_gold";

    /** Character portrait key for a character id. */
    public static String character(int id)
    {
        return "char_" + id;
    }

    private static final Map<String, Bitmap> sIcons = new HashMap<>();

    private BanjoTooieIcons() {}

    public static synchronized Bitmap get(String key)
    {
        return sIcons.get(key);
    }

    public static synchronized void put(String key, Bitmap icon)
    {
        if (icon != null) sIcons.put(key, icon);
    }

    public static synchronized boolean isEmpty()
    {
        return sIcons.isEmpty();
    }
}
