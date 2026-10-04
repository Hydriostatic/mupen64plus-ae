package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import com.sun.jna.Native;

import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.jni.AeBridgeLibrary;

/**
 * Jet Force Gemini (USA): twin-stick camera done from the emulator.
 *
 * The camera work happens in the core (mupen64plus-core main/jfg_camera.c, ported from
 * Project64JFG); this class only hands it the raw state of the gamepad, independently
 * of the controller profile, since the scheme needs the right stick and both triggers
 * as themselves. Outside gameplay (menus, pause, cutscenes) the core ignores this and the
 * game gets the normal controller profile.
 */
public final class JetForceGeminiCamera
{
    private static final String TAG = "JetForceGeminiCamera";

    // Must match JFG_PAD_* in jfg_camera.h
    private static final int PAD_A = 1;
    private static final int PAD_B = 1 << 1;
    private static final int PAD_X = 1 << 2;
    private static final int PAD_Y = 1 << 3;
    private static final int PAD_L1 = 1 << 4;
    private static final int PAD_R1 = 1 << 5;
    private static final int PAD_L2 = 1 << 6;
    private static final int PAD_R2 = 1 << 7;
    private static final int PAD_START = 1 << 8;
    private static final int PAD_SELECT = 1 << 9;
    private static final int PAD_L3 = 1 << 10;
    private static final int PAD_R3 = 1 << 11;
    private static final int PAD_DUP = 1 << 12;
    private static final int PAD_DDOWN = 1 << 13;
    private static final int PAD_DLEFT = 1 << 14;
    private static final int PAD_DRIGHT = 1 << 15;
    private static final int PAD_HAT_MASK = PAD_DUP | PAD_DDOWN | PAD_DLEFT | PAD_DRIGHT;

    private static volatile boolean sActive = false;
    private static AeBridgeLibrary sBridge;
    private static boolean sBridgeFailed = false;

    private static int sKeyButtons = 0;
    private static int sHatButtons = 0;
    private static float sLx, sLy, sRx, sRy, sLt, sRt;

    private JetForceGeminiCamera() {}

    public static boolean isJetForceGeminiUsa(String romHeaderName, byte countryCode)
    {
        return romHeaderName != null &&
                romHeaderName.trim().toUpperCase().startsWith("JET FORCE GEMINI") &&
                countryCode == 0x45; // 'E' = North America
    }

    /** Called when a game starts: enables the tap for Jet Force Gemini (USA) only. */
    public static void start(Context context, String romHeaderName, byte countryCode)
    {
        reset();
        sActive = false;
        if (!isJetForceGeminiUsa(romHeaderName, countryCode))
            return;
        if (bridge() == null)
            return;

        // The pad tap runs for the whole game so the camera can be switched on live
        JetForceGeminiCameraSettings settings = JetForceGeminiCameraSettings.load(context);
        applyAll(settings);
        sActive = true;
        boolean enabled = settings.get(0) >= 0.5f;
        Log.i(TAG, "Jet Force Gemini (USA): twin-stick camera " + (enabled ? "on" : "off"));
        if (enabled)
            Toast.makeText(context, R.string.jfgCamera_active, Toast.LENGTH_LONG).show();
    }

    /** True while a Jet Force Gemini (USA) game is running in this process. */
    public static boolean isRunning()
    {
        return sActive;
    }

    /** Sends every tunable to the core (only while the game runs in this process). */
    public static void applyAll(JetForceGeminiCameraSettings settings)
    {
        AeBridgeLibrary b = sBridge;
        if (b == null)
            return;
        for (int i = 0; i < JetForceGeminiCameraSettings.PARAMS.length; i++)
            b.aeJfgSetParam(JetForceGeminiCameraSettings.PARAMS[i].id, settings.get(i));
    }

    /** Sends one tunable to the core, live (only while the game runs in this process). */
    public static void apply(int index, float value)
    {
        AeBridgeLibrary b = sBridge;
        if (b != null && sActive)
            b.aeJfgSetParam(JetForceGeminiCameraSettings.PARAMS[index].id, value);
    }

    /** 0 = off, 1 = waiting for gameplay, 2 = camera active */
    public static int status()
    {
        AeBridgeLibrary b = sBridge;
        return b != null && sActive ? b.aeJfgStatus() : 0;
    }

    public static void stop()
    {
        sActive = false;
        reset();
    }

    private static void reset()
    {
        sKeyButtons = 0;
        sHatButtons = 0;
        sLx = sLy = sRx = sRy = sLt = sRt = 0;
    }

    private static boolean isGamepad(int source)
    {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** Every gamepad motion event, before the controller profile maps it. */
    public static void onMotion(MotionEvent event)
    {
        if (!sActive || event.getAction() != MotionEvent.ACTION_MOVE)
            return;
        final int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
                (source & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD)
            return;
        final InputDevice device = event.getDevice();

        sLx = event.getAxisValue(MotionEvent.AXIS_X);
        sLy = event.getAxisValue(MotionEvent.AXIS_Y);

        // The standard right stick is Z/RZ; a few pads put it on RX/RY and the
        // triggers on Z/RZ instead.
        boolean rightOnZ = true;
        if (device != null) {
            boolean hasZ = device.getMotionRange(MotionEvent.AXIS_Z, source) != null &&
                    device.getMotionRange(MotionEvent.AXIS_RZ, source) != null;
            boolean hasRx = device.getMotionRange(MotionEvent.AXIS_RX, source) != null &&
                    device.getMotionRange(MotionEvent.AXIS_RY, source) != null;
            boolean hasTriggers = device.getMotionRange(MotionEvent.AXIS_LTRIGGER, source) != null ||
                    device.getMotionRange(MotionEvent.AXIS_BRAKE, source) != null;
            rightOnZ = hasZ && (hasTriggers || !hasRx);
        }
        if (rightOnZ) {
            sRx = event.getAxisValue(MotionEvent.AXIS_Z);
            sRy = event.getAxisValue(MotionEvent.AXIS_RZ);
            sLt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            sRt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        } else {
            sRx = event.getAxisValue(MotionEvent.AXIS_RX);
            sRy = event.getAxisValue(MotionEvent.AXIS_RY);
            sLt = Math.max(0, event.getAxisValue(MotionEvent.AXIS_Z));
            sRt = Math.max(0, event.getAxisValue(MotionEvent.AXIS_RZ));
        }

        final float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        final float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        sHatButtons = (hatX < -0.5f ? PAD_DLEFT : 0) | (hatX > 0.5f ? PAD_DRIGHT : 0) |
                (hatY < -0.5f ? PAD_DUP : 0) | (hatY > 0.5f ? PAD_DDOWN : 0);
        push();
    }

    /** Every gamepad key event, before the controller profile maps it. */
    public static void onKey(int keyCode, KeyEvent event)
    {
        if (!sActive || !isGamepad(event.getSource()))
            return;
        final int bit = buttonFor(keyCode);
        if (bit == 0)
            return;
        if (event.getAction() == KeyEvent.ACTION_DOWN)
            sKeyButtons |= bit;
        else if (event.getAction() == KeyEvent.ACTION_UP)
            sKeyButtons &= ~bit;
        else
            return;
        push();
    }

    private static int buttonFor(int keyCode)
    {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return PAD_A;
            case KeyEvent.KEYCODE_BUTTON_B: return PAD_B;
            case KeyEvent.KEYCODE_BUTTON_X: return PAD_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return PAD_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return PAD_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return PAD_R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return PAD_L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return PAD_R2;
            case KeyEvent.KEYCODE_BUTTON_START: return PAD_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return PAD_SELECT;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return PAD_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return PAD_R3;
            case KeyEvent.KEYCODE_DPAD_UP: return PAD_DUP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return PAD_DDOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return PAD_DLEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return PAD_DRIGHT;
            default: return 0;
        }
    }

    private static void push()
    {
        AeBridgeLibrary b = bridge();
        if (b == null)
            return;
        int buttons = sKeyButtons | (sHatButtons & PAD_HAT_MASK);
        b.aeJfgSetPad(buttons, sLx, sLy, sRx, sRy, sLt, sRt);
    }

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
}
