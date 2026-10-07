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

import android.util.Log;

import com.sun.jna.Native;

import paulscode.android.mupen64plusae.jni.AeBridgeLibrary;

/** Reads the running game's N64 memory (RDRAM) through the core's debugger interface. */
final class GameMemory
{
    private static final String TAG = "GameMemory";

    /** Size of N64 RDRAM with the Expansion Pak. */
    static final int RDRAM_SIZE = 0x800000;

    private static AeBridgeLibrary sBridge;
    private static boolean sBridgeFailed = false;

    private GameMemory() {}

    private static AeBridgeLibrary bridge()
    {
        if (sBridge == null && !sBridgeFailed) {
            try {
                // Same native library (and process) as the running emulator core
                sBridge = Native.load("ae-bridge", AeBridgeLibrary.class);
            } catch (Throwable e) {
                Log.e(TAG, "Couldn't load ae-bridge", e);
                sBridgeFailed = true;
            }
        }
        return sBridge;
    }

    /**
     * Copy raw N64 memory (big-endian byte order) from a physical RDRAM offset or a KSEG0/KSEG1
     * address. Returns false if no game is running.
     */
    static boolean read(int address, byte[] out, int length)
    {
        AeBridgeLibrary b = bridge();
        return b != null && b.aeReadRdram(address, out, length) == length;
    }
}
