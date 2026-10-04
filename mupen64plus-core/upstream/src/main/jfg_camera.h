/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *   Mupen64plus - jfg_camera.h                                            *
 *   Jet Force Gemini (USA) twin-stick camera, done from the emulator      *
 *                                                                         *
 *   Ported from Project64JFG (https://github.com/djorgri/Project64JFG),   *
 *   GPLv2, using the US addresses of the Ryan-Myers/Jet-Force-Gemini      *
 *   decompilation that project is built on.                               *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

#ifndef M64P_MAIN_JFG_CAMERA_H
#define M64P_MAIN_JFG_CAMERA_H

#include <stdint.h>

#include "api/m64p_types.h"

/* Raw gamepad buttons the frontend reports, see JfgCameraSetPad */
enum
{
    JFG_PAD_A      = 1 << 0,
    JFG_PAD_B      = 1 << 1,
    JFG_PAD_X      = 1 << 2,
    JFG_PAD_Y      = 1 << 3,
    JFG_PAD_L1     = 1 << 4,
    JFG_PAD_R1     = 1 << 5,
    JFG_PAD_L2     = 1 << 6,
    JFG_PAD_R2     = 1 << 7,
    JFG_PAD_START  = 1 << 8,
    JFG_PAD_SELECT = 1 << 9,
    JFG_PAD_L3     = 1 << 10,
    JFG_PAD_R3     = 1 << 11,
    JFG_PAD_DUP    = 1 << 12,
    JFG_PAD_DDOWN  = 1 << 13,
    JFG_PAD_DLEFT  = 1 << 14,
    JFG_PAD_DRIGHT = 1 << 15
};

/* Core hooks */
void jfg_camera_rom_started(void);
void jfg_camera_state_loaded(void);
void jfg_camera_new_vi(void);
/* Rewrites the plugin-format BUTTONS value the game is about to read from a controller */
void jfg_camera_filter_input(int control, uint32_t* value);

/* Frontend entry points (looked up with dlsym) */
EXPORT void CALL JfgCameraConfigure(int enabled, int speed, int invertY);
/* Sticks are -1..1 with Android's signs (right and down positive), triggers 0..1 */
EXPORT void CALL JfgCameraSetPad(unsigned int buttons, float lx, float ly, float rx, float ry, float lt, float rt);
/* 0 = not Jet Force Gemini (USA) / off, 1 = waiting for gameplay, 2 = camera active */
EXPORT int CALL JfgCameraStatus(void);

#endif
