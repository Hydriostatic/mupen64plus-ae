package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Properties;

import paulscode.android.mupen64plusae.R;

/**
 * The tunables of the Jet Force Gemini camera (see mupen64plus-core main/jfg_camera.h).
 *
 * They are kept in a small text file in the app's files folder rather than in the shared
 * preferences: the game runs in its own process, and a file read fresh each time keeps the
 * in-game panel and the settings screen from overwriting each other.
 */
public final class JetForceGeminiCameraSettings
{
    private static final String TAG = "JfgCameraSettings";
    private static final String FILE_NAME = "jfg_camera.properties";

    public static final int TYPE_SWITCH = 0;
    public static final int TYPE_SLIDER = 1;

    public static final int FORMAT_NUMBER = 0;
    public static final int FORMAT_PERCENT = 1;
    public static final int FORMAT_MULTIPLIER = 2;
    public static final int FORMAT_CURVE = 3;

    /** One tunable. The id is the core's JFG_PARAM_* value. */
    public static final class Param
    {
        public final int id;
        public final String key;
        public final int type;
        public final float def, min, max, step;
        public final int format;
        public final int titleRes;
        public final int summaryRes;

        Param(int id, String key, int type, float def, float min, float max, float step, int format,
              int titleRes, int summaryRes)
        {
            this.id = id;
            this.key = key;
            this.type = type;
            this.def = def;
            this.min = min;
            this.max = max;
            this.step = step;
            this.format = format;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
        }

        public float clamp(float value)
        {
            if (Float.isNaN(value)) return def;
            if (type == TYPE_SWITCH) return value >= 0.5f ? 1f : 0f;
            value = Math.max(min, Math.min(max, value));
            return min + Math.round((value - min) / step) * step;
        }

        public int steps()
        {
            return Math.round((max - min) / step);
        }

        public String describe(float value)
        {
            switch (format) {
                case FORMAT_PERCENT:
                    return Math.round(value * 100f) + "%";
                case FORMAT_MULTIPLIER:
                    return String.format(Locale.US, "×%.2f", value);
                case FORMAT_CURVE:
                    return String.format(Locale.US, "%.1f", value);
                default:
                    return value == Math.rint(value) ? String.valueOf((int) value)
                            : String.format(Locale.US, "%.2f", value);
            }
        }
    }

    // Ids must match the JFG_PARAM_* enum in jfg_camera.h
    public static final Param[] PARAMS = {
        new Param(0, "enabled", TYPE_SWITCH, 1, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_enabled, R.string.jfgParam_enabled_summary),
        new Param(1, "speed_x", TYPE_SLIDER, 5, 1, 20, 0.5f, FORMAT_NUMBER,
                R.string.jfgParam_speedX, 0),
        new Param(2, "speed_y", TYPE_SLIDER, 5, 1, 20, 0.5f, FORMAT_NUMBER,
                R.string.jfgParam_speedY, 0),
        new Param(3, "invert_x", TYPE_SWITCH, 0, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_invertX, 0),
        new Param(4, "invert_y", TYPE_SWITCH, 0, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_invertY, R.string.jfgParam_invertY_summary),
        new Param(5, "curve", TYPE_SLIDER, 2, 1, 3, 0.1f, FORMAT_CURVE,
                R.string.jfgParam_curve, R.string.jfgParam_curve_summary),
        new Param(6, "deadzone_right", TYPE_SLIDER, 0.20f, 0, 0.6f, 0.01f, FORMAT_PERCENT,
                R.string.jfgParam_deadzoneRight, 0),
        new Param(7, "deadzone_left", TYPE_SLIDER, 0.20f, 0, 0.6f, 0.01f, FORMAT_PERCENT,
                R.string.jfgParam_deadzoneLeft, 0),
        new Param(8, "trigger", TYPE_SLIDER, 0.25f, 0.05f, 0.95f, 0.05f, FORMAT_PERCENT,
                R.string.jfgParam_trigger, R.string.jfgParam_trigger_summary),
        new Param(9, "height_limit", TYPE_SLIDER, 200, 0, 400, 10, FORMAT_NUMBER,
                R.string.jfgParam_heightLimit, R.string.jfgParam_heightLimit_summary),
        new Param(10, "align_on_aim", TYPE_SWITCH, 1, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_alignOnAim, R.string.jfgParam_alignOnAim_summary),
        new Param(11, "free_in_jump", TYPE_SWITCH, 1, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_freeInJump, 0),
        new Param(12, "keep_game_cameras", TYPE_SWITCH, 1, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_keepGameCameras, R.string.jfgParam_keepGameCameras_summary),
        new Param(13, "swap_ab", TYPE_SWITCH, 0, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_swapAB, 0),
        new Param(14, "swap_xy", TYPE_SWITCH, 0, 0, 1, 1, FORMAT_NUMBER,
                R.string.jfgParam_swapXY, 0),
        new Param(15, "aim_speed", TYPE_SLIDER, 1, 0.25f, 3, 0.05f, FORMAT_MULTIPLIER,
                R.string.jfgParam_aimSpeed, R.string.jfgParam_aimSpeed_summary),
    };

    private final File mFile;
    private final float[] mValues = new float[PARAMS.length];

    private JetForceGeminiCameraSettings(Context context)
    {
        mFile = new File(context.getFilesDir(), FILE_NAME);
        for (int i = 0; i < PARAMS.length; i++)
            mValues[i] = PARAMS[i].def;
    }

    /** Reads the file afresh; missing or unreadable values fall back to the defaults. */
    public static JetForceGeminiCameraSettings load(Context context)
    {
        JetForceGeminiCameraSettings settings = new JetForceGeminiCameraSettings(context);
        if (!settings.mFile.exists())
            return settings;
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(settings.mFile)) {
            props.load(in);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read " + settings.mFile, e);
            return settings;
        }
        for (int i = 0; i < PARAMS.length; i++) {
            String text = props.getProperty(PARAMS[i].key);
            if (text == null) continue;
            try {
                settings.mValues[i] = PARAMS[i].clamp(Float.parseFloat(text.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return settings;
    }

    public float get(int index)
    {
        return mValues[index];
    }

    public void set(int index, float value)
    {
        mValues[index] = PARAMS[index].clamp(value);
    }

    public void resetToDefaults()
    {
        for (int i = 0; i < PARAMS.length; i++)
            mValues[i] = PARAMS[i].def;
    }

    public void save()
    {
        Properties props = new Properties();
        for (int i = 0; i < PARAMS.length; i++)
            props.setProperty(PARAMS[i].key, String.format(Locale.US, "%s", trim(mValues[i])));
        try (OutputStream out = new FileOutputStream(mFile)) {
            props.store(out, "Jet Force Gemini (USA) twin-stick camera");
        } catch (Exception e) {
            Log.w(TAG, "Couldn't write " + mFile, e);
        }
    }

    private static String trim(float value)
    {
        return value == Math.rint(value) ? String.valueOf((int) value)
                : String.format(Locale.US, "%.3f", value).replaceAll("0+$", "");
    }
}
