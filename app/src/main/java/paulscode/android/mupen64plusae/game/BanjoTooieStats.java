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

/**
 * Reads live Banjo-Tooie (USA) game state from N64 memory for the second-screen stats panel.
 *
 * Memory layout sources:
 * - Consumable counters (eggs, feathers, ...): u16 at 0x8011B080 + index * 0x0C, from the
 *   Archipelago Banjo-Tooie tools (MIT, (c) 2025 g0goTBC, Austin, jjjj12212); matches the USA
 *   GameShark codes for eggs (8111B080), grenade eggs (8011B0A5) and red feathers (8011B0C9).
 * - Collection flags (Jiggies, notes, Jinjos, ...): bit flags in the block pointed to by the
 *   u32 at 0x8012C770; byte/bit positions from the same Archipelago data.
 * - Current character: byte at 0x8012704C (USA GameShark character modifier code).
 * - Health: 3-byte entry per character at 0x8011B640 + character * 3, bytes [1] = current and
 *   [2] = maximum (USA Action Replay health codes).
 */
public final class BanjoTooieStats
{
    private static final String TAG = "BanjoTooieStats";

    // --- Addresses (physical RDRAM offsets) ---
    private static final int CONSUMABLES = 0x11B080;
    private static final int CONSUMABLE_STRIDE = 0x0C;
    private static final int CONSUMABLE_COUNT = 21;
    private static final int HEALTH_TABLE = 0x11B640;
    private static final int HEALTH_TABLE_SIZE = 0x40;
    private static final int CHARACTER = 0x12704C;
    private static final int FLAG_BLOCK_PTR = 0x12C770;
    private static final int FLAG_BLOCK_SIZE = 160;
    static final int CHARACTER_SLOTS = HEALTH_TABLE_SIZE / 3;

    /** Characters shown on the Character & health page, in display order. */
    static final int[] PLAYABLE_CHARACTERS = {
            0x01, 0x0A, 0x0B, 0x0D, 0x02, 0x06, 0x07, 0x08, 0x0C, 0x0F, 0x10, 0x12, 0x13, 0x0E
    };

    /** Normal carrying capacity (before the in-game "double" cheats), for the ammo bars. */
    static int capacity(int consumable)
    {
        switch (consumable) {
            case BLUE_EGGS: return 100;
            case FIRE_EGGS: return 50;
            case ICE_EGGS: return 50;
            case GRENADE_EGGS: return 25;
            case CLOCKWORK_EGGS: return 10;
            case RED_FEATHERS: return 100;
            case GOLD_FEATHERS: return 10;
            default: return 0;
        }
    }

    // --- Consumable indices ---
    static final int BLUE_EGGS = 0, FIRE_EGGS = 1, ICE_EGGS = 2, GRENADE_EGGS = 3,
            CLOCKWORK_EGGS = 4, RED_FEATHERS = 6, GOLD_FEATHERS = 7, GLOWBOS = 8,
            HONEYCOMBS = 9, CHEATO_PAGES = 10, DOUBLOONS = 14;

    // --- Collection flags: (byte << 3) | bit within the flag block ---
    /** JIGGIES (81) */
    static final int[] JIGGY_FLAGS = {
        0x0228, 0x0229, 0x022A, 0x022B, 0x022C, 0x022D, 0x022E, 0x022F, 0x0230, 0x0231,
        0x0232, 0x0233, 0x0234, 0x0235, 0x0236, 0x0237, 0x0238, 0x0239, 0x023A, 0x023B,
        0x023C, 0x023D, 0x023E, 0x023F, 0x0240, 0x0241, 0x0242, 0x0243, 0x0244, 0x0245,
        0x0246, 0x0247, 0x0248, 0x0249, 0x024A, 0x024B, 0x024C, 0x024D, 0x024E, 0x024F,
        0x0250, 0x0251, 0x0252, 0x0253, 0x0254, 0x0255, 0x0256, 0x0257, 0x0258, 0x0259,
        0x025A, 0x025B, 0x025C, 0x025D, 0x025E, 0x025F, 0x0260, 0x0261, 0x0262, 0x0263,
        0x0264, 0x0265, 0x0266, 0x0267, 0x0268, 0x0269, 0x026A, 0x026B, 0x026C, 0x026D,
        0x026E, 0x026F, 0x0270, 0x0271, 0x0272, 0x0273, 0x0274, 0x0275, 0x0276, 0x0277,
        0x0281
    };
    /** JINJO_FAMILY (9) */
    static final int[] JINJO_FAMILY_JIGGY_FLAGS = {
        0x0278, 0x0279, 0x027A, 0x027B, 0x027C, 0x027D, 0x027E, 0x027F, 0x0280
    };
    /** NOTES (144) */
    static final int[] NOTE_NEST_FLAGS = {
        0x0427, 0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F, 0x0430,
        0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0438, 0x0439, 0x043A, 0x043B,
        0x043C, 0x043D, 0x043E, 0x043F, 0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445,
        0x0446, 0x0447, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F, 0x0450,
        0x0451, 0x0452, 0x0453, 0x0454, 0x0455, 0x0456, 0x0457, 0x0458, 0x045A, 0x045B,
        0x045C, 0x045D, 0x045E, 0x045F, 0x0460, 0x0461, 0x0462, 0x0463, 0x0464, 0x0465,
        0x0466, 0x0467, 0x0468, 0x0469, 0x046B, 0x046C, 0x046D, 0x046E, 0x046F, 0x0470,
        0x0471, 0x0472, 0x0473, 0x0474, 0x0475, 0x0476, 0x0477, 0x0478, 0x0479, 0x047A,
        0x047C, 0x047D, 0x047E, 0x047F, 0x0480, 0x0481, 0x0482, 0x0483, 0x0484, 0x0485,
        0x0486, 0x0487, 0x0488, 0x0489, 0x048A, 0x048B, 0x048D, 0x048E, 0x048F, 0x0490,
        0x0491, 0x0492, 0x0493, 0x0494, 0x0495, 0x0496, 0x0497, 0x0498, 0x0499, 0x049A,
        0x049B, 0x049C, 0x049E, 0x049F, 0x04A0, 0x04A1, 0x04A2, 0x04A3, 0x04A4, 0x04A5,
        0x04A6, 0x04A7, 0x04A8, 0x04A9, 0x04AA, 0x04AB, 0x04AC, 0x04AD, 0x04AF, 0x04B0,
        0x04B1, 0x04B2, 0x04B3, 0x04B4, 0x04B5, 0x04B6, 0x04B7, 0x04B8, 0x04B9, 0x04BA,
        0x04BB, 0x04BC, 0x04BD, 0x04BE
    };
    /** TREBLE (9) */
    static final int[] TREBLE_CLEF_FLAGS = {
        0x0437, 0x0448, 0x0459, 0x046A, 0x047B, 0x048C, 0x049D, 0x04AE, 0x04BF
    };
    /** JINJOS (45) */
    static final int[] JINJO_FLAGS = {
        0x01CC, 0x01CD, 0x01CE, 0x01CF, 0x01D0, 0x01D1, 0x01D2, 0x01D3, 0x01D4, 0x01D5,
        0x01D6, 0x01D7, 0x01D8, 0x01D9, 0x01DA, 0x01DB, 0x01DC, 0x01DD, 0x01DE, 0x01DF,
        0x01E0, 0x01E1, 0x01E2, 0x01E3, 0x01E4, 0x01E5, 0x01E6, 0x01E7, 0x01E8, 0x01E9,
        0x01EA, 0x01EB, 0x01EC, 0x01ED, 0x01EE, 0x01EF, 0x01F0, 0x01F1, 0x01F2, 0x01F3,
        0x01F4, 0x01F5, 0x01F6, 0x01F7, 0x01F8
    };
    /** PAGES (25) */
    static final int[] CHEATO_PAGE_FLAGS = {
        0x02B3, 0x02B4, 0x02B5, 0x02B6, 0x02B7, 0x02B8, 0x02B9, 0x02BA, 0x02BB, 0x02BC,
        0x02BD, 0x02BE, 0x02BF, 0x02C0, 0x02C1, 0x02C2, 0x02C3, 0x02C4, 0x02C5, 0x02C6,
        0x02C7, 0x02C8, 0x02C9, 0x02CA, 0x02CB
    };
    /** HONEYCOMB (25) */
    static final int[] EMPTY_HONEYCOMB_FLAGS = {
        0x01FA, 0x01FB, 0x01FC, 0x01FD, 0x01FE, 0x01FF, 0x0200, 0x0201, 0x0202, 0x0203,
        0x0204, 0x0205, 0x0206, 0x0207, 0x0208, 0x0209, 0x020A, 0x020B, 0x020C, 0x020D,
        0x020E, 0x020F, 0x0210, 0x0211, 0x0212
    };
    /** GLOWBO (18) */
    static final int[] GLOWBO_FLAGS = {
        0x002E, 0x0217, 0x0218, 0x0219, 0x021A, 0x021B, 0x021C, 0x021D, 0x021E, 0x021F,
        0x0220, 0x0221, 0x0222, 0x0223, 0x0224, 0x0225, 0x0226, 0x0227
    };
    /** DOUBLOON (30) */
    static final int[] DOUBLOON_FLAGS = {
        0x0117, 0x0118, 0x0119, 0x011A, 0x011B, 0x011C, 0x011D, 0x011E, 0x011F, 0x0120,
        0x0121, 0x0122, 0x0123, 0x0124, 0x0125, 0x0126, 0x0127, 0x0128, 0x0129, 0x012A,
        0x012B, 0x012C, 0x012D, 0x012E, 0x012F, 0x0130, 0x0131, 0x0132, 0x0133, 0x0134
    };
    // highest flag byte used: 151


    /** Is this ROM Banjo-Tooie (USA)? The addresses above are only valid for that version. */
    public static boolean isBanjoTooieUsa(String romHeaderName, byte countryCode)
    {
        return romHeaderName != null &&
                romHeaderName.trim().toUpperCase().startsWith("BANJO TOOIE") &&
                countryCode == 0x45; // 'E' = North America
    }

    /** A snapshot of everything shown on the panel. */
    public static final class Snapshot
    {
        public boolean valid;
        public int character;
        public int health = -1, maxHealth = -1;
        public final int[] consumables = new int[CONSUMABLE_COUNT];
        public int jiggies, notes, jinjos, cheatoPages, emptyHoneycombs, glowbos, doubloons;
        /** Health of every character, indexed by character id (-1 = no entry). */
        public final int[] allHealth = new int[CHARACTER_SLOTS];
        public final int[] allMaxHealth = new int[CHARACTER_SLOTS];
        /** Raw values for checking the addresses on a real game. */
        public int flagBlockAddress;
        public final int[] healthBytes = new int[3];
    }

    private static AeBridgeLibrary sBridge;
    private static boolean sBridgeFailed = false;

    private final byte[] mConsumables = new byte[CONSUMABLE_COUNT * CONSUMABLE_STRIDE];
    private final byte[] mHealth = new byte[HEALTH_TABLE_SIZE];
    private final byte[] mOne = new byte[4];
    private final byte[] mFlags = new byte[FLAG_BLOCK_SIZE];

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

    private static boolean read(int address, byte[] out, int length)
    {
        AeBridgeLibrary b = bridge();
        return b != null && b.aeReadRdram(address, out, length) == length;
    }

    private static int u8(byte[] a, int i) { return a[i] & 0xFF; }
    private static int u16(byte[] a, int i) { return ((a[i] & 0xFF) << 8) | (a[i + 1] & 0xFF); }
    private static int u32(byte[] a, int i)
    {
        return ((a[i] & 0xFF) << 24) | ((a[i + 1] & 0xFF) << 16) | ((a[i + 2] & 0xFF) << 8) | (a[i + 3] & 0xFF);
    }

    private int countFlags(int[] flags)
    {
        int n = 0;
        for (int f : flags) {
            int byteIndex = f >> 3;
            if (byteIndex < mFlags.length && (mFlags[byteIndex] & (1 << (f & 7))) != 0) n++;
        }
        return n;
    }

    /** Read the current state. Returns a snapshot with valid = false if no game data is there yet. */
    public Snapshot read(Snapshot s)
    {
        if (s == null) s = new Snapshot();
        s.valid = false;

        if (!read(CONSUMABLES, mConsumables, mConsumables.length)) return s;
        for (int i = 0; i < CONSUMABLE_COUNT; i++) {
            s.consumables[i] = u16(mConsumables, i * CONSUMABLE_STRIDE);
        }

        if (read(CHARACTER, mOne, 1)) s.character = u8(mOne, 0);

        s.health = s.maxHealth = -1;
        if (read(HEALTH_TABLE, mHealth, mHealth.length)) {
            for (int id = 0; id < CHARACTER_SLOTS; id++) {
                int cur = u8(mHealth, id * 3 + 1), max = u8(mHealth, id * 3 + 2);
                s.allHealth[id] = cur;
                s.allMaxHealth[id] = max > 0 ? max : cur;
            }
            int entry = s.character * 3;
            if (s.character > 0 && entry + 2 < mHealth.length) {
                s.healthBytes[0] = u8(mHealth, entry);
                s.healthBytes[1] = u8(mHealth, entry + 1);
                s.healthBytes[2] = u8(mHealth, entry + 2);
                s.health = s.healthBytes[1];
                s.maxHealth = s.healthBytes[2] > 0 ? s.healthBytes[2] : s.healthBytes[1];
            }
        }

        // The flag block moves around, so follow the pointer each time
        s.flagBlockAddress = 0;
        if (read(FLAG_BLOCK_PTR, mOne, 4)) {
            int ptr = u32(mOne, 0);
            if ((ptr & 0xFF800000) == 0x80000000) { // a valid KSEG0 RDRAM address
                s.flagBlockAddress = ptr;
                if (read(ptr & 0x7FFFFF, mFlags, mFlags.length)) {
                    s.jiggies = countFlags(JIGGY_FLAGS) + countFlags(JINJO_FAMILY_JIGGY_FLAGS);
                    s.notes = countFlags(NOTE_NEST_FLAGS) * 5 + countFlags(TREBLE_CLEF_FLAGS) * 20;
                    s.jinjos = countFlags(JINJO_FLAGS);
                    s.cheatoPages = countFlags(CHEATO_PAGE_FLAGS);
                    s.emptyHoneycombs = countFlags(EMPTY_HONEYCOMB_FLAGS);
                    s.glowbos = countFlags(GLOWBO_FLAGS);
                    s.doubloons = countFlags(DOUBLOON_FLAGS);
                    s.valid = true;
                }
            }
        }
        return s;
    }

    /** Display name for the character byte, or null if unknown. */
    public static String characterName(int id)
    {
        switch (id) {
            case 0x01: return "Banjo & Kazooie";
            case 0x02: return "Snowball";
            case 0x06: return "Bee";
            case 0x07: return "Washing Machine";
            case 0x08: return "Stony";
            case 0x0A: return "Banjo";
            case 0x0B: return "Kazooie";
            case 0x0C: return "Submarine";
            case 0x0D: return "Mumbo";
            case 0x0E: return "Golden Goliath";
            case 0x0F: return "Detonator";
            case 0x10: return "Van";
            case 0x12: return "T-Rex";
            case 0x13: return "Daddy T-Rex";
            default: return null;
        }
    }
}
