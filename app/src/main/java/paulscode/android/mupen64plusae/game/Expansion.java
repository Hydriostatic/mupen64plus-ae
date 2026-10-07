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
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A second-screen expansion (.exp): a zip with a manifest.json that says which ROM it is for,
 * which values to read from the game's memory, and how to lay them out on the second screen,
 * plus optional images and a font. See EXPANSIONS.md for the format.
 *
 * Expansions hold no code, only data: the emulator reads memory and draws what they describe.
 */
public final class Expansion
{
    private static final String TAG = "Expansion";
    public static final int FORMAT = 1;

    // --- Identity ---
    public final String id, name, version, author, game;
    public final File file;

    // --- ROM match ---
    private final String mHeader, mCountry, mCrc, mMd5;

    // --- Theme ---
    final int background, panel, text, label, accent;
    final String backgroundImage, fontFile;
    final Nine signImage, tileImage, barImage, buttonImage;
    final int[] titleColors;

    // --- Content ---
    final String title;
    final int columns;
    final List<Tile> tiles = new ArrayList<>();
    final List<Tile> bar = new ArrayList<>();
    final String barLabel;
    private final Map<String, JSONObject> mValues = new HashMap<>();

    /** A tile (or a bar item): a label, an icon and a value template like "{jiggies}/90". */
    static final class Tile
    {
        final String label, icon, value;
        Tile(String label, String icon, String value) { this.label = label; this.icon = icon; this.value = value; }
    }

    /** An image drawn 9-slice: corners keep their shape, the middle stretches. */
    static final class Nine
    {
        final String image;
        final int left, top, right, bottom;
        /** Optional spots in the image's own pixels (0 = default): label centre from the top,
         *  value centre from the bottom, where the content starts on the left (e.g. after a
         *  sign painted into a bar). */
        final int labelY, valueY, contentLeft;
        Nine(String image, int[] insets, int labelY, int valueY, int contentLeft)
        {
            this.image = image;
            left = insets[0]; top = insets[1]; right = insets[2]; bottom = insets[3];
            this.labelY = labelY; this.valueY = valueY; this.contentLeft = contentLeft;
        }
    }

    private Expansion(File file, JSONObject m) throws JSONException
    {
        this.file = file;
        if (m.optInt("format", 0) != FORMAT) throw new JSONException("unsupported format " + m.opt("format"));
        id = m.getString("id").replaceAll("[^A-Za-z0-9._-]", "_");
        name = m.optString("name", id);
        version = m.optString("version", "1");
        author = m.optString("author", "");
        game = m.optString("game", name);

        JSONObject match = m.getJSONObject("match");
        mHeader = match.optString("header", "").trim().toUpperCase(Locale.US);
        mCountry = match.optString("country", "");
        mCrc = match.optString("crc", "").trim().toUpperCase(Locale.US);
        mMd5 = match.optString("md5", "").trim().toLowerCase(Locale.US);
        if (mHeader.isEmpty() && mCrc.isEmpty() && mMd5.isEmpty()) throw new JSONException("match needs header, crc or md5");

        JSONObject theme = m.optJSONObject("theme");
        if (theme == null) theme = new JSONObject();
        background = color(theme.optString("background"), 0xFF24190F);
        panel = color(theme.optString("panel"), 0xFF6B3F1C);
        text = color(theme.optString("text"), 0xFFFFFFFF);
        label = color(theme.optString("label"), 0xFFF8E6C2);
        accent = color(theme.optString("accent"), 0xFFFFC93C);
        backgroundImage = theme.optString("background_image", null);
        fontFile = theme.optString("font", null);
        signImage = nine(theme.optJSONObject("sign_image"));
        tileImage = nine(theme.optJSONObject("tile_image"));
        barImage = nine(theme.optJSONObject("bar_image"));
        Nine button = nine(theme.optJSONObject("button_image"));
        buttonImage = button != null ? button : tileImage;
        JSONArray tc = theme.optJSONArray("title_colors");
        titleColors = new int[tc != null ? tc.length() : 0];
        for (int i = 0; i < titleColors.length; i++) titleColors[i] = color(tc.optString(i), 0xFFFFFFFF);

        JSONObject screen = m.getJSONObject("screen");
        title = screen.optString("title", name);
        columns = Math.max(1, Math.min(6, screen.optInt("columns", 4)));
        readTiles(screen.optJSONArray("tiles"), tiles);
        JSONObject b = screen.optJSONObject("bar");
        barLabel = b != null ? b.optString("label", "") : "";
        if (b != null) readTiles(b.optJSONArray("items"), bar);

        JSONObject values = m.optJSONObject("values");
        if (values != null) {
            for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                String k = it.next();
                mValues.put(k, values.getJSONObject(k));
            }
        }
    }

    private static void readTiles(JSONArray a, List<Tile> out) throws JSONException
    {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.getJSONObject(i);
            out.add(new Tile(t.optString("label", ""), t.optString("icon", null), t.optString("value", "")));
        }
    }

    private static Nine nine(JSONObject o)
    {
        if (o == null || !o.has("image")) return null;
        JSONArray in = o.optJSONArray("insets");
        int[] insets = {0, 0, 0, 0};
        for (int i = 0; in != null && i < 4 && i < in.length(); i++) insets[i] = in.optInt(i);
        return new Nine(o.optString("image"), insets, o.optInt("label_y", 0), o.optInt("value_y", 0),
                o.optInt("content_left", 0));
    }

    private static int color(String s, int def)
    {
        if (s == null || s.isEmpty()) return def;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** Read and check an .exp file. Throws with a readable message if it isn't a valid expansion. */
    public static Expansion load(File file) throws IOException
    {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry e = zip.getEntry("manifest.json");
            if (e == null) throw new IOException("manifest.json not found");
            String json = new String(readAll(zip.getInputStream(e)), "UTF-8");
            return new Expansion(file, new JSONObject(json));
        } catch (JSONException ex) {
            throw new IOException("manifest.json: " + ex.getMessage());
        }
    }

    static byte[] readAll(InputStream in) throws IOException
    {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private final Map<String, Bitmap> mImages = new HashMap<>();
    private ZipFile mZip;

    private synchronized ZipFile zip() throws IOException
    {
        if (mZip == null) mZip = new ZipFile(file);
        return mZip;
    }

    /** An image from the expansion (cached), or null. */
    synchronized Bitmap image(String path)
    {
        if (path == null || path.isEmpty()) return null;
        if (mImages.containsKey(path)) return mImages.get(path);
        Bitmap b = null;
        try {
            ZipEntry e = zip().getEntry(path);
            if (e != null) {
                byte[] data = readAll(zip().getInputStream(e));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
        } catch (IOException | OutOfMemoryError ex) {
            Log.w(TAG, "Couldn't load " + path, ex);
        }
        mImages.put(path, b);
        return b;
    }

    private Typeface mTypeface;
    private boolean mTypefaceLoaded;

    /** The expansion's font, or null for the default. */
    synchronized Typeface typeface(File cacheDir)
    {
        if (mTypefaceLoaded) return mTypeface;
        mTypefaceLoaded = true;
        if (fontFile == null || fontFile.isEmpty()) return null;
        try {
            ZipEntry e = zip().getEntry(fontFile);
            if (e == null) return null;
            File out = new File(cacheDir, "exp_" + id + "_" + new File(fontFile).getName());
            try (InputStream in = zip().getInputStream(e); FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            mTypeface = Typeface.createFromFile(out);
        } catch (Exception ex) {
            Log.w(TAG, "Couldn't load font " + fontFile, ex);
        }
        return mTypeface;
    }

    synchronized void close()
    {
        if (mZip != null) {
            try { mZip.close(); } catch (IOException ignored) {}
            mZip = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ROM match
    // ---------------------------------------------------------------------------------------------

    /** Does this expansion belong to the given ROM? */
    public boolean matches(String header, byte countryCode, String crc, String md5)
    {
        if (!mMd5.isEmpty()) return md5 != null && mMd5.equalsIgnoreCase(md5.trim());
        if (!mCrc.isEmpty() && (crc == null || !mCrc.equals(crc.trim().toUpperCase(Locale.US)))) return false;
        if (!mHeader.isEmpty() && (header == null || !header.trim().toUpperCase(Locale.US).startsWith(mHeader))) return false;
        if (!mCountry.isEmpty() && mCountry.charAt(0) != (char) (countryCode & 0xFF)) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Values: read from the game's memory and filled into "{name}" templates
    // ---------------------------------------------------------------------------------------------

    /** Reads the values once per refresh; keeps "keep_last" lookups between refreshes. */
    final class Reader
    {
        private final Map<String, Object> mCache = new HashMap<>();
        private final Map<String, Object> mKept = new HashMap<>();
        private final byte[] mBuf = new byte[4];
        private final Map<Integer, byte[]> mBlocks = new HashMap<>();
        long sessionMs;
        boolean gameRunning;

        /** Start a new refresh. Returns false if no game memory can be read. */
        boolean refresh()
        {
            mCache.clear();
            mBlocks.clear();
            gameRunning = BanjoTooieStats.readRaw(0, mBuf, 4);
            return gameRunning;
        }

        /** Fill "{name}" placeholders. Unknown names stay as they are. */
        String format(String template)
        {
            if (template == null || template.indexOf('{') < 0) return template;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < template.length()) {
                int open = template.indexOf('{', i);
                if (open < 0) { sb.append(template, i, template.length()); break; }
                int close = template.indexOf('}', open);
                if (close < 0) { sb.append(template, i, template.length()); break; }
                sb.append(template, i, open);
                String key = template.substring(open + 1, close);
                Object v = value(key, 0);
                sb.append(v != null ? v : template.substring(open, close + 1));
                i = close + 1;
            }
            return sb.toString();
        }

        Object value(String key, int depth)
        {
            if ("time".equals(key)) return time(sessionMs);
            if (mCache.containsKey(key)) return mCache.get(key);
            JSONObject def = mValues.get(key);
            Object v = def != null && depth < 8 ? eval(key, def, depth) : null;
            mCache.put(key, v);
            return v;
        }

        private long num(String key, int depth)
        {
            Object v = value(key, depth + 1);
            return v instanceof Long ? (Long) v : 0;
        }

        private Object eval(String key, JSONObject d, int depth)
        {
            String type = d.optString("type", "u8");
            try {
                switch (type) {
                    case "u8": case "s8": case "u16": case "s16": case "u32": {
                        Integer addr = address(d);
                        if (addr == null) return null;
                        long v = read(addr, type);
                        return scale(d, v);
                    }
                    case "flags": {
                        Integer base = address(d);
                        if (base == null) return null;
                        JSONArray bits = d.getJSONArray("bits");
                        int max = 0;
                        for (int i = 0; i < bits.length(); i++) max = Math.max(max, bits.getInt(i) >> 3);
                        byte[] block = block(base, max + 1);
                        if (block == null) return null;
                        long count = 0;
                        for (int i = 0; i < bits.length(); i++) {
                            int f = bits.getInt(i);
                            if ((block[f >> 3] & (1 << (f & 7))) != 0) count++;
                        }
                        String mode = d.optString("mode", "count");
                        if ("any".equals(mode)) return count > 0 ? 1L : 0L;
                        if ("all".equals(mode)) return count == bits.length() ? 1L : 0L;
                        return scale(d, count);
                    }
                    case "sum": {
                        JSONArray terms = d.getJSONArray("terms");
                        long total = 0;
                        for (int i = 0; i < terms.length(); i++) {
                            Object t = terms.get(i);
                            if (t instanceof JSONObject) {
                                JSONObject o = (JSONObject) t;
                                total += num(o.getString("value"), depth) * o.optLong("times", 1);
                            } else {
                                total += num(String.valueOf(t), depth);
                            }
                        }
                        return total;
                    }
                    case "lookup": {
                        long v = num(d.getString("value"), depth);
                        JSONObject table = d.getJSONObject("table");
                        String hit = table.optString(String.valueOf(v), null);
                        if (hit == null) hit = table.optString("0x" + Long.toHexString(v).toUpperCase(Locale.US), null);
                        if (hit == null) hit = table.optString("0x" + Long.toHexString(v), null);
                        if (hit != null) {
                            if (d.optBoolean("keep_last", false)) mKept.put(key, hit);
                            return hit;
                        }
                        if (d.optBoolean("keep_last", false) && mKept.containsKey(key)) return mKept.get(key);
                        return d.optString("default", "");
                    }
                    case "const":
                        return d.opt("value") instanceof Number ? ((Number) d.opt("value")).longValue() : d.optString("value");
                    default:
                        return null;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Bad value '" + key + "': " + e.getMessage());
                return null;
            }
        }

        private Object scale(JSONObject d, long v)
        {
            if (d.has("times")) v *= d.optLong("times", 1);
            if (d.has("add")) v += d.optLong("add", 0);
            return v;
        }

        /** "addr", or "ptr" (address holding a pointer) + "offset". Physical RDRAM offset or null. */
        private Integer address(JSONObject d) throws JSONException
        {
            if (d.has("ptr")) {
                int p = parse(d.getString("ptr"));
                if (!BanjoTooieStats.readRaw(p, mBuf, 4)) return null;
                int ptr = ((mBuf[0] & 0xFF) << 24) | ((mBuf[1] & 0xFF) << 16) | ((mBuf[2] & 0xFF) << 8) | (mBuf[3] & 0xFF);
                if ((ptr & 0xFF800000) != 0x80000000) return null; // not a valid pointer (yet)
                return (ptr & 0x7FFFFF) + parse(d.optString("offset", "0"));
            }
            return parse(d.getString("addr")) & 0x7FFFFF;
        }

        private byte[] block(int addr, int size)
        {
            byte[] b = mBlocks.get(addr);
            if (b != null && b.length >= size) return b;
            b = new byte[size];
            if (!BanjoTooieStats.readRaw(addr, b, size)) return null;
            mBlocks.put(addr, b);
            return b;
        }

        private long read(int addr, String type)
        {
            int n = type.endsWith("8") ? 1 : type.endsWith("16") ? 2 : 4;
            if (!BanjoTooieStats.readRaw(addr, mBuf, n)) return 0;
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (mBuf[i] & 0xFF);
            if ("s8".equals(type)) v = (byte) v;
            if ("s16".equals(type)) v = (short) v;
            return v;
        }
    }

    static int parse(String s)
    {
        s = s.trim();
        return s.startsWith("0x") || s.startsWith("0X") ? (int) Long.parseLong(s.substring(2), 16) : Integer.parseInt(s);
    }

    static String time(long ms)
    {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }
}
