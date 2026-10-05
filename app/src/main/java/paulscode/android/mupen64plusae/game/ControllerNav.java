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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Controller navigation (D-pad, analog stick, A = select, B = back) for a window that receives
 * keys handed over from the other screen.
 *
 * Android only moves the selection with the D-pad / stick inside the window that has input
 * focus; keys passed from one screen's window to the other skip that step, so it is done here.
 */
final class ControllerNav
{
    private static final float STICK_ON = 0.6f, STICK_OFF = 0.3f;

    private int mStickX = 0, mStickY = 0;
    private boolean mConfirmDown = false;
    private boolean mInA = false;

    /** Select the highlighted item (on release). */
    private void confirm(Activity activity, boolean down, int repeat)
    {
        if (down) {
            mConfirmDown = repeat == 0;
        } else if (mConfirmDown) {
            mConfirmDown = false;
            View focus = activity.getCurrentFocus();
            if (focus != null && !focus.isInTouchMode()) focus.performClick();
            else move(activity, View.FOCUS_DOWN); // nothing selected yet: select the first item
        }
    }

    /** Handle a key the window didn't handle itself. Returns true if it was used. */
    boolean onKey(Activity activity, KeyEvent event)
    {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        int direction = direction(code);
        if (direction != 0) {
            if (down) move(activity, direction);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            // Android turns an unused A into "select" (DPAD_CENTER); do the same, so lists work
            if (mInA) return true;
            mInA = true;
            try {
                boolean handled = activity.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                        event.getAction(), KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount()));
                if (!handled) confirm(activity, down, event.getRepeatCount());
            } finally {
                mInA = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            confirm(activity, down, event.getRepeatCount());
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            if (!down) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            }
            return true;
        }
        return false;
    }

    /** Analog stick / hat as D-pad. Returns true if it was used. */
    boolean onMotion(Activity activity, MotionEvent event)
    {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
                (event.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return false;
        }
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < STICK_ON) x = event.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(y) < STICK_ON) y = event.getAxisValue(MotionEvent.AXIS_Y);

        int sx = Math.abs(x) >= STICK_ON ? (int) Math.signum(x) : Math.abs(x) <= STICK_OFF ? 0 : mStickX;
        int sy = Math.abs(y) >= STICK_ON ? (int) Math.signum(y) : Math.abs(y) <= STICK_OFF ? 0 : mStickY;

        if (sx != mStickX && sx != 0) move(activity, sx > 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT);
        if (sy != mStickY && sy != 0) move(activity, sy > 0 ? View.FOCUS_DOWN : View.FOCUS_UP);
        mStickX = sx;
        mStickY = sy;
        return true;
    }

    private static int direction(int code)
    {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    /** Move the selection; if nothing is selected (or the screen was just touched), select the first item. */
    private static void move(Activity activity, int direction)
    {
        View focus = activity.getCurrentFocus();
        if (focus == null || focus.isInTouchMode()) {
            View content = activity.findViewById(android.R.id.content);
            if (focus != null) {
                focus.requestFocusFromTouch(); // leave touch mode, keep the current item
            } else if (content != null) {
                content.requestFocusFromTouch();
            }
            return;
        }
        View next = focus.focusSearch(direction);
        if (next != null && next != focus) next.requestFocus(direction);
    }
}
