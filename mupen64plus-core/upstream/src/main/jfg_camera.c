/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *   Mupen64plus - jfg_camera.c                                            *
 *   Jet Force Gemini (USA) twin-stick camera, done from the emulator      *
 *                                                                         *
 *   Ported from Project64JFG (https://github.com/djorgri/Project64JFG),   *
 *   GPLv2: its free orbit camera, the camera code it rewrites in RDRAM    *
 *   and its gamepad scheme. Addresses are those of the US retail 1.0      *
 *   build (CRC 8A6009B6 / 94ACE150) from the Ryan-Myers/Jet-Force-Gemini  *
 *   decompilation. Nothing in the ROM is changed: every word written      *
 *   here is checked against its original first and put back on exit.     *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

#include "jfg_camera.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

#include "api/callbacks.h"
#include "api/m64p_types.h"
#include "device/device.h"
#include "device/r4300/r4300_core.h"
#include "device/rdram/rdram.h"
#include "main/main.h"
#include "main/netplay.h"
#include "main/rom.h"
#include "osal/preproc.h"

/* ------------------------------------------------------------------------ */
/* The build                                                                 */
/* ------------------------------------------------------------------------ */

#define JFG_US_CRC1 0x8A6009B6u
#define JFG_US_CRC2 0x94ACE150u

/* Camera code (cameraUpdate, func_8002CF6C, and cameraTopDown) */
#define CAMERA_CODE_START          0x8002CF6Cu
#define CAMERA_CODE_END            0x8002ED94u
#define CAMERA_CLAMP_BRANCH        0x8002D128u
#define CAMERA_CENTER_BRANCH       0x8002D154u
#define CAMERA_ORBIT_GATE_BRANCH   0x8002DEC8u
#define CAMERA_ORBIT_CENTER_BRANCH 0x8002DED8u
#define CAMERA_ORBIT_BRANCH        0x8002DF94u
#define CAMERA_HEIGHT_BLEND_BASE   0x8002E51Cu
#define CAMERA_LOOK_HELPER_CALL    0x8002E814u
#define CAMERA_YAW_HELPER_CALL     0x8002EA34u
#define CAMERA_PITCH_HELPER_CALL   0x8002EA5Cu
#define CAMERA_TOPDOWN_ENTRY       0x8002EB6Cu
/* Alignment padding after two retail functions, zero in the ROM */
#define CAMERA_HELPER_BASE         0x800968CCu
#define CAMERA_HELPER_SIZE         0x44u
#define CAMERA_TOPDOWN_HELPER_BASE 0x80098D08u
#define CAMERA_TOPDOWN_HELPER_SIZE 0x18u

/* A zero gap in the game's data: per-player native camera height, per-player
 * orbit height offset, and the counter the top-down helper advances */
#define CAMERA_NATIVE_Y_TABLE      0x8009F228u
#define CAMERA_HEIGHT_TABLE        0x8009F238u
#define CAMERA_HEIGHT_OFFSET_OLD   0x8009F248u
#define CAMERA_TOPDOWN_COUNTER     0x8009F24Cu

/* Game state */
#define ROBOT_MISSION_ADDR         0x800A3208u
#define CONTROL_MODE_NORMAL_TABLE  0x800A18B4u
#define CONTROL_MODE_EXPERT_TABLE  0x800A18D8u
#define OS_ACTIVE_QUEUE_ADDR       0x800A9E8Cu
#define PLAYER_LIST_ADDR           0x800F2D0Cu
#define PLAYER_COUNT_ADDR          0x800F2D10u
#define DISABLE_JOY_ADDR           0x800F6DBCu
#define CONTROL_CAMERA_ADDR        0x800F6DC0u
#define CAMERA_ACTIVE_OVERRIDE     0x800F6E58u
#define CAMERA_ACTIVE_OVERRIDE_STRIDE 0x2Cu
#define CAMERA_ARRAY_ADDR          0x800FA4D0u
#define CAMERA_STRUCT_SIZE         0x4Cu
#define CAMERA_COUNT               4u
#define LOBBY_CAMERA_IN_USE        0x800FB080u
#define STATIC_CAMERA_IN_USE       0x800FB084u
#define PAUSE_MODE_ADDR            0x800FD7BDu
#define SELECTED_CONTROL_MODES     0x800FF38Du
#define ANIMSEQ_CAMERA_ADDR        0x801045B8u

/* Objects */
#define OBJECT_PLAYER_DATA_OFFSET  0x68u
#define OBJECT_YAW_OFFSET          0x00u
#define TRANSFORM_X_OFFSET         0x0Cu
#define TRANSFORM_Y_OFFSET         0x10u
#define TRANSFORM_Z_OFFSET         0x14u
#define CAMERA_RENDER_PITCH_OFFSET 0x02u
#define CAMERA_PITCH_OFFSET        0x4Au

#define PLAYER_INDEX_OFFSET               0x000u
#define PLAYER_TYPE_OFFSET                0x001u
#define PLAYER_CAMERA_ORBIT_YAW_OFFSET    0x104u
#define PLAYER_CAMERA_YAW_OFFSET          0x10Au
#define PLAYER_CAMERA_CENTER_OFFSET       0x10Cu
#define PLAYER_CAMERA_CONTROL_FLAGS       0x10Du
#define PLAYER_MOVEMENT_YAW_OFFSET        0x11Cu
#define PLAYER_ALT_CAMERA_BASE_YAW        0x128u
#define PLAYER_CAMERA_TRANSITION_OFFSET   0x198u
#define PLAYER_CAMERA_BEHAVIOR_OFFSET     0x19Du
#define PLAYER_CAMERA_FORCE_OFFSET        0x19Fu
#define PLAYER_CAMERA_SCRIPT_OFFSET       0x1F9u
#define PLAYER_CAMERA_OVERRIDE_OFFSET     0x1FAu
#define PLAYER_CAMERA_PATH_OFFSET         0x1FCu
#define PLAYER_CAMERA_MODE_OFFSET         0x568u
#define PLAYER_CAMERA_OBJECT_OFFSET       0x5C0u

#define CAMERA_MODE_NORMAL     0
#define CAMERA_MODE_CROUCH     1
#define CAMERA_MODE_PRONE      2
#define CAMERA_MODE_JUMP       3
#define CAMERA_MODE_CROUCH_AIM 5
#define CAMERA_MODE_MANUAL_AIM 11
#define CAMERA_MODE_BOSS_AIM   32

/* Tuning, as Project64JFG ships it */
#define STICK_LIMIT               80
#define STICK_DEAD_ZONE           0.2f
#define DIGITAL_THRESHOLD         0.5f
#define TRIGGER_THRESHOLD         0.25f
#define COUNTS_PER_SPEED          2.0f
#define CAMERA_YAW_SENSITIVITY    64
#define CAMERA_HEIGHT_SENSITIVITY 1.5f
#define CAMERA_HEIGHT_LIMIT       200.0f
#define ELEVATION_MIN_TANGENT     0.087488664f
#define ELEVATION_MAX_TANGENT     1.732050808f
#define N64_ANGLE_TO_RADIANS      0.000095873799f
#define TOPDOWN_HOLD_FRAMES       6

/* N64 controller bits as the game reads them (hardware order) */
#define N64_A      0x8000u
#define N64_B      0x4000u
#define N64_Z      0x2000u
#define N64_START  0x1000u
#define N64_DUP    0x0800u
#define N64_DDOWN  0x0400u
#define N64_DLEFT  0x0200u
#define N64_DRIGHT 0x0100u
#define N64_R      0x0010u

/* ------------------------------------------------------------------------ */
/* Code patches                                                              */
/* ------------------------------------------------------------------------ */

struct jfg_patch
{
    uint32_t address;
    uint32_t original;
    uint32_t replacement;
};

/* Leaf helpers the patched camera code calls, written into padding. The yaw
 * and pitch helper returns the target angle unsmoothed in the on-foot modes
 * and defers to dAngle (0x80033FA4) otherwise; the look helper drops the
 * look-ahead in the same modes. The top-down helper counts top-down camera
 * frames so the free orbit keeps out of them. */
static const struct jfg_patch g_helper_patches[] =
{
    { CAMERA_HELPER_BASE + 0x00, 0x00000000, 0x92080568 }, /* lbu  t0, 0x568(s0)  */
    { CAMERA_HELPER_BASE + 0x04, 0x00000000, 0x3108FFFC }, /* andi t0, t0, 0xFFFC */
    { CAMERA_HELPER_BASE + 0x08, 0x00000000, 0x00000000 },
    { CAMERA_HELPER_BASE + 0x0C, 0x00000000, 0x15000003 }, /* bnez t0, +3         */
    { CAMERA_HELPER_BASE + 0x10, 0x00000000, 0x00A01025 }, /* move v0, a1         */
    { CAMERA_HELPER_BASE + 0x14, 0x00000000, 0x03E00008 }, /* jr   ra             */
    { CAMERA_HELPER_BASE + 0x18, 0x00000000, 0x00000000 },
    { CAMERA_HELPER_BASE + 0x1C, 0x00000000, 0x0800CFE9 }, /* j    dAngle         */
    { CAMERA_HELPER_BASE + 0x20, 0x00000000, 0x92080568 }, /* lbu  t0, 0x568(s0)  */
    { CAMERA_HELPER_BASE + 0x24, 0x00000000, 0x3108FFFC },
    { CAMERA_HELPER_BASE + 0x28, 0x00000000, 0x00000000 },
    { CAMERA_HELPER_BASE + 0x2C, 0x00000000, 0x15000003 },
    { CAMERA_HELPER_BASE + 0x30, 0x00000000, 0x8FA200F0 }, /* lw   v0, 0xF0(sp)   */
    { CAMERA_HELPER_BASE + 0x34, 0x00000000, 0xAFA00098 }, /* sw   zero, 0x98(sp) */
    { CAMERA_HELPER_BASE + 0x38, 0x00000000, 0xAFA000A0 }, /* sw   zero, 0xA0(sp) */
    { CAMERA_HELPER_BASE + 0x3C, 0x00000000, 0x03E00008 }, /* jr   ra             */
    { CAMERA_HELPER_BASE + 0x40, 0x00000000, 0x00000000 }, /* (delay slot)        */

    { CAMERA_TOPDOWN_HELPER_BASE + 0x00, 0x00000000, 0x3C08800A }, /* lui  t0, 0x800A        */
    { CAMERA_TOPDOWN_HELPER_BASE + 0x04, 0x00000000, 0x8D09F24C }, /* lw   t1, -0xDB4(t0)    */
    { CAMERA_TOPDOWN_HELPER_BASE + 0x08, 0x00000000, 0x3C06800F }, /* lui  a2, 0x800F (moved)*/
    { CAMERA_TOPDOWN_HELPER_BASE + 0x0C, 0x00000000, 0x25290001 }, /* addiu t1, t1, 1        */
    { CAMERA_TOPDOWN_HELPER_BASE + 0x10, 0x00000000, 0x0800BADD }, /* j    0x8002EB74        */
    { CAMERA_TOPDOWN_HELPER_BASE + 0x14, 0x00000000, 0xAD09F24C }, /* sw   t1, -0xDB4(t0)    */
};

/* The jump into the top-down helper; installed after the helpers, removed first */
static const struct jfg_patch g_topdown_entry_patches[] =
{
    { CAMERA_TOPDOWN_ENTRY + 0x00, 0x44866000, 0x08026342 }, /* j 0x80098D08      */
    { CAMERA_TOPDOWN_ENTRY + 0x04, 0x3C06800F, 0x44866000 }, /* mtc1 a2, f12      */
};

/* The free orbit itself: opens the game's own orbit path, calls the helpers and
 * adds the per-player height offset (indexed by the player number in s0) to the
 * camera's target height. In only while the player orbits. */
static const struct jfg_patch g_orbit_patches[] =
{
    { CAMERA_CLAMP_BRANCH,        0x14200004, 0x10000008 },
    { CAMERA_CENTER_BRANCH,       0x1160002F, 0x1000002F },
    { CAMERA_ORBIT_GATE_BRANCH,   0x1140003F, 0x00000000 },
    { CAMERA_ORBIT_CENTER_BRANCH, 0x1560003B, 0x00000000 },
    { CAMERA_ORBIT_BRANCH,        0x11C00006, 0x00000000 },
    { CAMERA_LOOK_HELPER_CALL,    0x8FA200F0, 0x0C025A3B }, /* jal CAMERA_HELPER_BASE + 0x20 */
    { CAMERA_YAW_HELPER_CALL,     0x0C00CFE9, 0x0C025A33 }, /* jal CAMERA_HELPER_BASE        */
    { CAMERA_PITCH_HELPER_CALL,   0x0C00CFE9, 0x0C025A33 },

    { CAMERA_HEIGHT_BLEND_BASE + 0x00, 0x8D230000, 0x92020000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x04, 0xC7A40090, 0xC7A40090 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x08, 0xC46C0010, 0x00021080 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x0C, 0xC7A800A8, 0x3C01800A },
    { CAMERA_HEIGHT_BLEND_BASE + 0x10, 0x460C2181, 0x00220821 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x14, 0x46083482, 0xE424F228 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x18, 0x460C9280, 0xC426F238 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x1C, 0xE46A0010, 0xC46C0010 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x20, 0x8D230000, 0x46062100 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x24, 0xC7A40094, 0x460C2181 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x28, 0xC4620014, 0x46083482 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x2C, 0xC7A800A8, 0x460C9280 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x30, 0x46022181, 0xE46A0010 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x34, 0x46083482, 0xC7A40094 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x38, 0x46029280, 0xC4620014 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x3C, 0xE46A0014, 0x46022181 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x40, 0x8D230000, 0x46083482 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x44, 0x00000000, 0x46029280 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x48, 0xC464000C, 0xE46A0014 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x4C, 0x00000000, 0xC464000C },
    { CAMERA_HEIGHT_BLEND_BASE + 0x50, 0xE4640018, 0xC4660010 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x54, 0x8D230000, 0xC4680014 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x58, 0x00000000, 0xE4640018 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x5C, 0xC4660010, 0xE466001C },
    { CAMERA_HEIGHT_BLEND_BASE + 0x60, 0x00000000, 0xE4680020 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x64, 0xE466001C, 0x00000000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x68, 0x8D230000, 0x00000000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x6C, 0x00000000, 0x00000000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x70, 0xC4680014, 0x00000000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x74, 0x00000000, 0x00000000 },
    { CAMERA_HEIGHT_BLEND_BASE + 0x78, 0xE4680020, 0x00000000 },
};

#define COUNT_OF(a) (sizeof(a) / sizeof((a)[0]))

/* ------------------------------------------------------------------------ */
/* State                                                                     */
/* ------------------------------------------------------------------------ */

/* Written by the frontend's input thread, read on the emulation thread. A torn
 * read only mixes two consecutive samples, which is harmless here. */
static volatile int      g_cfg_enabled = 1;
static volatile int      g_cfg_speed = 5;
static volatile int      g_cfg_invert_y = 0;
static volatile int      g_pad_seen = 0;
static volatile uint32_t g_pad_buttons = 0;
static volatile float    g_pad_lx, g_pad_ly, g_pad_rx, g_pad_ry, g_pad_lt, g_pad_rt;

struct jfg_pad
{
    uint32_t buttons;
    float lx, ly, rx, ry;
    int aim, fire;
};

struct jfg_orbit
{
    int override_active;
    int override_suspended;
    uint32_t tracked_camera;
    uint32_t tracked_player_object;
    int orbit_yaw_initialized;
    int elevation_ready;
    float height_offset;
    int16_t orbit_yaw;
    float carry_x, carry_y;
};

struct jfg_eval
{
    uint32_t player_object;
    uint32_t player_data;
    uint32_t camera;
    uint32_t joy_disabled;
    uint8_t camera_mode;
    int basic_state_available;
    int constrained_state_available;
    int normal_camera;
    int mouse_camera_allowed;
    int jump_camera_mode;
    int free_jump_camera_allowed;
    int free_camera_blocked_by_jump;
    int free_camera_state_allowed;
    int enable_free_orbit;
};

static int g_rom_supported = 0;
static int g_installed = 0;          /* helpers in, as far as we know */
static int g_maybe_dirty = 0;        /* RDRAM may hold our words (after a state load) */
static int g_mismatch_logged = 0;
static int g_camera_patch_applied = 0;
static int g_scheme_active = 0;      /* the gamepad scheme drives port one */
static int g_status = 0;
static struct jfg_orbit g_orbit;
static int g_topdown_initialized = 0;
static uint32_t g_topdown_counter = 0;
static int g_topdown_hold = 0;
static int g_queued_scroll = 0;      /* +1 next weapon, -1 previous: a one-poll impulse */
static uint32_t g_prev_scroll_buttons = 0;

/* ------------------------------------------------------------------------ */
/* RDRAM access                                                              */
/* ------------------------------------------------------------------------ */

static int to_phys(uint32_t address, uint32_t size, uint32_t* phys)
{
    uint32_t p;
    if ((address & 0xC0000000u) != 0x80000000u || g_dev.rdram.dram == NULL)
        return 0;
    p = address & 0x1FFFFFFFu;
    if ((uint64_t)p + size > (uint64_t)g_dev.rdram.dram_size)
        return 0;
    *phys = p;
    return 1;
}

static int is_rdram(uint32_t address, uint32_t size)
{
    uint32_t p;
    return to_phys(address, size, &p);
}

static int rd_u8(uint32_t address, uint8_t* value)
{
    uint32_t p;
    if (!to_phys(address, 1, &p)) return 0;
    *value = ((const uint8_t*)g_dev.rdram.dram)[p ^ S8];
    return 1;
}

static int rd_s16(uint32_t address, int16_t* value)
{
    uint32_t p;
    if ((address & 1) || !to_phys(address, 2, &p)) return 0;
    *value = *(const int16_t*)((const uint8_t*)g_dev.rdram.dram + (p ^ S16));
    return 1;
}

static int rd_u32(uint32_t address, uint32_t* value)
{
    uint32_t p;
    if ((address & 3) || !to_phys(address, 4, &p)) return 0;
    *value = g_dev.rdram.dram[p >> 2];
    return 1;
}

static int rd_f32(uint32_t address, float* value)
{
    uint32_t bits;
    if (!rd_u32(address, &bits)) return 0;
    memcpy(value, &bits, sizeof(bits));
    return 1;
}

static int wr_u8(uint32_t address, uint8_t value)
{
    uint32_t p;
    if (!to_phys(address, 1, &p)) return 0;
    ((uint8_t*)g_dev.rdram.dram)[p ^ S8] = value;
    return 1;
}

static int wr_s16(uint32_t address, int16_t value)
{
    uint32_t p;
    if ((address & 1) || !to_phys(address, 2, &p)) return 0;
    *(int16_t*)((uint8_t*)g_dev.rdram.dram + (p ^ S16)) = value;
    return 1;
}

static int wr_u32(uint32_t address, uint32_t value)
{
    uint32_t p;
    if ((address & 3) || !to_phys(address, 4, &p)) return 0;
    g_dev.rdram.dram[p >> 2] = value;
    return 1;
}

static int wr_f32(uint32_t address, float value)
{
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return wr_u32(address, bits);
}

static int wr_code(uint32_t address, uint32_t value)
{
    if (!wr_u32(address, value)) return 0;
    invalidate_r4300_cached_code(&g_dev.r4300, address, 4);
    return 1;
}

/* ------------------------------------------------------------------------ */
/* Guarding code rewrites                                                    */
/* ------------------------------------------------------------------------ */

static int address_in_camera_code(uint32_t address)
{
    return (address >= CAMERA_CODE_START && address < CAMERA_CODE_END) ||
           (address - CAMERA_HELPER_BASE < CAMERA_HELPER_SIZE) ||
           (address - CAMERA_TOPDOWN_HELPER_BASE < CAMERA_TOPDOWN_HELPER_SIZE);
}

/* True while the running thread, or a thread the game pre-empted, is inside the
 * camera code or the helpers (or returns into them): rewriting them then would
 * resume it in the middle of a different instruction sequence. libultra's
 * OSThread keeps the saved $sp, $ra and pc at +0xF4, +0x104 and +0x11C. */
static int camera_code_in_use(void)
{
    uint32_t pc = *r4300_pc(&g_dev.r4300);
    uint32_t ra = (uint32_t)r4300_regs(&g_dev.r4300)[31];
    uint32_t thread = 0, running = 0, count;

    if (address_in_camera_code(pc) || address_in_camera_code(ra))
        return 1;

    if (!rd_u32(OS_ACTIVE_QUEUE_ADDR, &thread) || !rd_u32(OS_ACTIVE_QUEUE_ADDR + 4, &running))
        return 0;

    for (count = 0; count < 64 && is_rdram(thread, 0x120); count++)
    {
        uint32_t priority = 0, next = 0, tpc = 0, tra = 0;
        if (!rd_u32(thread + 0x04, &priority) || priority == 0xFFFFFFFFu ||
            !rd_u32(thread + 0x0C, &next) ||
            !rd_u32(thread + 0x104, &tra) || !rd_u32(thread + 0x11C, &tpc))
            break;
        if (thread != running && (address_in_camera_code(tpc) || address_in_camera_code(tra)))
            return 1;
        thread = next;
    }
    return 0;
}

/* Every word must be either the original or our replacement; anything else
 * means this is not the code we expect, and nothing is written. */
static int patches_match(const struct jfg_patch* patches, size_t count)
{
    size_t i;
    for (i = 0; i < count; i++)
    {
        uint32_t current;
        if (!rd_u32(patches[i].address, &current))
            return 0;
        if (current != patches[i].original && current != patches[i].replacement)
            return 0;
    }
    return 1;
}

static int patches_need_change(const struct jfg_patch* patches, size_t count, int enable)
{
    size_t i;
    for (i = 0; i < count; i++)
    {
        uint32_t current;
        if (rd_u32(patches[i].address, &current) &&
            current != (enable ? patches[i].replacement : patches[i].original))
            return 1;
    }
    return 0;
}

static void patches_write(const struct jfg_patch* patches, size_t count, int enable)
{
    size_t i;
    for (i = 0; i < count; i++)
    {
        uint32_t current, desired = enable ? patches[i].replacement : patches[i].original;
        if (rd_u32(patches[i].address, &current) && current != desired)
            wr_code(patches[i].address, desired);
    }
}

/* Installs (or removes) the helpers and the top-down hook, and puts the free
 * orbit words in or out. Returns 1 when memory now matches the request, 0 on a
 * signature mismatch, -1 when the change has to wait for the camera code to be
 * idle. Helpers go in before anything that calls them and come out last. */
static int set_camera_code(int install, int free_orbit)
{
    const int orbit = install && free_orbit;
    int changes;

    if (!patches_match(g_helper_patches, COUNT_OF(g_helper_patches)) ||
        !patches_match(g_topdown_entry_patches, COUNT_OF(g_topdown_entry_patches)) ||
        !patches_match(g_orbit_patches, COUNT_OF(g_orbit_patches)))
    {
        if (!g_mismatch_logged)
        {
            DebugMessage(M64MSG_WARNING, "JFG camera: camera code does not match the US 1.0 build, leaving it alone");
            g_mismatch_logged = 1;
        }
        return 0;
    }

    changes = patches_need_change(g_helper_patches, COUNT_OF(g_helper_patches), install) ||
              patches_need_change(g_topdown_entry_patches, COUNT_OF(g_topdown_entry_patches), install) ||
              patches_need_change(g_orbit_patches, COUNT_OF(g_orbit_patches), orbit);
    if (!changes)
        return 1;
    if (camera_code_in_use())
        return -1;

    if (install)
    {
        patches_write(g_helper_patches, COUNT_OF(g_helper_patches), 1);
        patches_write(g_topdown_entry_patches, COUNT_OF(g_topdown_entry_patches), 1);
        patches_write(g_orbit_patches, COUNT_OF(g_orbit_patches), orbit);
    }
    else
    {
        patches_write(g_orbit_patches, COUNT_OF(g_orbit_patches), 0);
        patches_write(g_topdown_entry_patches, COUNT_OF(g_topdown_entry_patches), 0);
        patches_write(g_helper_patches, COUNT_OF(g_helper_patches), 0);
    }
    return 1;
}

/* ------------------------------------------------------------------------ */
/* Game state                                                                */
/* ------------------------------------------------------------------------ */

static int get_player_data(uint32_t* player_object, uint32_t* player_data)
{
    uint32_t count, list;
    return rd_u32(PLAYER_COUNT_ADDR, &count) && count != 0 && count <= 4 &&
           rd_u32(PLAYER_LIST_ADDR, &list) && is_rdram(list, 4) &&
           rd_u32(list, player_object) &&
           is_rdram(*player_object, OBJECT_PLAYER_DATA_OFFSET + 4) &&
           rd_u32(*player_object + OBJECT_PLAYER_DATA_OFFSET, player_data) &&
           is_rdram(*player_data, PLAYER_CAMERA_OBJECT_OFFSET + 4);
}

static int get_control_camera(uint32_t* camera)
{
    const uint32_t end = CAMERA_ARRAY_ADDR + CAMERA_STRUCT_SIZE * CAMERA_COUNT;
    return rd_u32(CONTROL_CAMERA_ADDR, camera) &&
           *camera >= CAMERA_ARRAY_ADDR && *camera < end &&
           (*camera - CAMERA_ARRAY_ADDR) % CAMERA_STRUCT_SIZE == 0;
}

static int get_player_camera(uint8_t player_index, uint32_t* camera)
{
    if (player_index >= CAMERA_COUNT)
        return 0;
    *camera = CAMERA_ARRAY_ADDR + player_index * CAMERA_STRUCT_SIZE;
    return is_rdram(*camera, CAMERA_STRUCT_SIZE);
}

static int is_manual_aim_mode(uint8_t mode)
{
    return mode == CAMERA_MODE_MANUAL_AIM || mode == CAMERA_MODE_CROUCH_AIM || mode == CAMERA_MODE_BOSS_AIM;
}

static void write_height_offset(uint8_t player_index, float offset)
{
    if (player_index < 4)
        wr_f32(CAMERA_HEIGHT_TABLE + player_index * 4, offset);
}

static void clear_height_offsets(void)
{
    uint8_t i;
    for (i = 0; i < 4; i++)
        write_height_offset(i, 0.0f);
    wr_f32(CAMERA_HEIGHT_OFFSET_OLD, 0.0f);
}

static int topdown_camera_was_updated(uint32_t counter)
{
    if (!g_topdown_initialized)
    {
        g_topdown_initialized = 1;
        g_topdown_counter = counter;
        if (counter != 0)
            g_topdown_hold = TOPDOWN_HOLD_FRAMES;
    }
    else if (counter != g_topdown_counter)
    {
        g_topdown_counter = counter;
        g_topdown_hold = TOPDOWN_HOLD_FRAMES;
    }
    else if (g_topdown_hold > 0)
    {
        g_topdown_hold--;
    }
    return g_topdown_hold > 0;
}

/* Whether the game's ordinary follow camera is the one in charge: no cutscene,
 * lobby, static, scripted, forced or path camera, no top-down section. */
static int get_normal_camera_state(uint32_t player_data, int* normal_camera, int* mouse_camera_allowed)
{
    uint32_t camera_object, animseq_camera, lobby_camera, static_camera, topdown_counter, active_override;
    uint8_t player_index, player_type, camera_mode, control_flags, transition, behavior, forced, scripted, override_cam;
    int16_t camera_path;
    int topdown, available, standard;

    if (!rd_u8(player_data + PLAYER_INDEX_OFFSET, &player_index) || player_index >= CAMERA_COUNT ||
        !rd_u32(player_data + PLAYER_CAMERA_OBJECT_OFFSET, &camera_object) ||
        !rd_u32(ANIMSEQ_CAMERA_ADDR, &animseq_camera) ||
        !rd_u32(LOBBY_CAMERA_IN_USE, &lobby_camera) ||
        !rd_u32(STATIC_CAMERA_IN_USE, &static_camera) ||
        !rd_u32(CAMERA_TOPDOWN_COUNTER, &topdown_counter) ||
        !rd_u32(CAMERA_ACTIVE_OVERRIDE + player_index * CAMERA_ACTIVE_OVERRIDE_STRIDE, &active_override) ||
        !rd_u8(player_data + PLAYER_TYPE_OFFSET, &player_type) ||
        !rd_u8(player_data + PLAYER_CAMERA_MODE_OFFSET, &camera_mode) ||
        !rd_u8(player_data + PLAYER_CAMERA_CONTROL_FLAGS, &control_flags) ||
        !rd_u8(player_data + PLAYER_CAMERA_TRANSITION_OFFSET, &transition) ||
        !rd_u8(player_data + PLAYER_CAMERA_BEHAVIOR_OFFSET, &behavior) ||
        !rd_u8(player_data + PLAYER_CAMERA_FORCE_OFFSET, &forced) ||
        !rd_u8(player_data + PLAYER_CAMERA_SCRIPT_OFFSET, &scripted) ||
        !rd_u8(player_data + PLAYER_CAMERA_OVERRIDE_OFFSET, &override_cam) ||
        !rd_s16(player_data + PLAYER_CAMERA_PATH_OFFSET, &camera_path))
        return 0;

    topdown = topdown_camera_was_updated(topdown_counter);
    available = camera_object == 0 && animseq_camera == 0 && lobby_camera == 0 && static_camera == 0 &&
                active_override == 0 && (player_type & 3) != 3 &&
                forced == 0 && scripted == 0 && override_cam == 0 && camera_path < 0 && !topdown;
    standard = (control_flags & 3) == 0 && transition == 0 && behavior < 2;
    *normal_camera = available && camera_mode == CAMERA_MODE_NORMAL && standard;
    *mouse_camera_allowed = available &&
        (*normal_camera || (g_orbit.override_active && !is_manual_aim_mode(camera_mode)));
    return 1;
}

static float clamp_height(float value)
{
    if (value > CAMERA_HEIGHT_LIMIT) return CAMERA_HEIGHT_LIMIT;
    if (value < -CAMERA_HEIGHT_LIMIT) return -CAMERA_HEIGHT_LIMIT;
    return value;
}

static int is_camera_float(float value)
{
    return value > -1000000.0f && value < 1000000.0f;
}

/* Keeps the raised or lowered camera between about 5 and 60 degrees above the
 * player, measured from where the game itself would put it. */
static float clamp_elevation(uint8_t player_index, uint32_t player_object, uint32_t camera, float offset)
{
    float cx, cy, cz, px, pz, native_y, dx, dz, dist2, dist, tangent, target, lo, hi;
    int16_t pitch;

    offset = clamp_height(offset);
    if (!rd_f32(camera + TRANSFORM_X_OFFSET, &cx) || !rd_f32(camera + TRANSFORM_Y_OFFSET, &cy) ||
        !rd_f32(camera + TRANSFORM_Z_OFFSET, &cz) ||
        !rd_f32(player_object + TRANSFORM_X_OFFSET, &px) || !rd_f32(player_object + TRANSFORM_Z_OFFSET, &pz) ||
        player_index >= 4 || !rd_f32(CAMERA_NATIVE_Y_TABLE + player_index * 4, &native_y) ||
        !rd_s16(camera + CAMERA_RENDER_PITCH_OFFSET, &pitch) ||
        !is_camera_float(cx) || !is_camera_float(cy) || !is_camera_float(cz) ||
        !is_camera_float(px) || !is_camera_float(pz) || !is_camera_float(native_y))
        return offset;

    dx = cx - px;
    dz = cz - pz;
    dist2 = dx * dx + dz * dz;
    if (dist2 < 1.0f || !is_camera_float(dist2))
        return offset;
    dist = sqrtf(dist2);
    tangent = tanf((float)pitch * N64_ANGLE_TO_RADIANS);
    if (!is_camera_float(tangent) || tangent < -16.0f || tangent > 16.0f)
        return offset;

    target = cy - tangent * dist;
    lo = target + ELEVATION_MIN_TANGENT * dist - native_y;
    hi = target + ELEVATION_MAX_TANGENT * dist - native_y;
    if (lo > 0.0f) lo = 0.0f;
    if (hi < 0.0f) hi = 0.0f;
    if (offset < lo) offset = lo;
    if (offset > hi) offset = hi;
    return clamp_height(offset);
}

static void reset_orbit(void)
{
    g_orbit.override_active = 0;
    g_orbit.override_suspended = 0;
    g_orbit.tracked_camera = 0;
    g_orbit.tracked_player_object = 0;
    g_orbit.orbit_yaw_initialized = 0;
    g_orbit.elevation_ready = 0;
}

static int get_camera_base_yaw(uint32_t player_object, uint32_t player_data, int16_t* base_yaw)
{
    uint8_t player_type;
    int16_t facing;
    if (!rd_u8(player_data + PLAYER_TYPE_OFFSET, &player_type))
        return 0;
    if ((player_type & 3) == 3)
    {
        if (!rd_s16(player_data + PLAYER_ALT_CAMERA_BASE_YAW, &facing))
            return 0;
    }
    else if (!rd_s16(player_object + OBJECT_YAW_OFFSET, &facing))
        return 0;
    *base_yaw = (int16_t)(0x8000 - (int32_t)facing);
    return 1;
}

/* The game's aim camera sits behind the player: turn the player to the orbit
 * first so starting to aim keeps the direction you were looking in. */
static void align_player_to_orbit(uint32_t player_object, uint32_t player_data)
{
    uint8_t player_type;
    int16_t facing;
    if (!g_orbit.orbit_yaw_initialized || !rd_u8(player_data + PLAYER_TYPE_OFFSET, &player_type))
        return;
    facing = (int16_t)(0x8000 - (int32_t)g_orbit.orbit_yaw);
    wr_s16(player_object + OBJECT_YAW_OFFSET, facing);
    wr_s16(player_data + PLAYER_MOVEMENT_YAW_OFFSET, facing);
    if ((player_type & 3) == 3)
        wr_s16(player_data + PLAYER_ALT_CAMERA_BASE_YAW, facing);
}

static void evaluate_orbit(int aim, struct jfg_eval* e)
{
    memset(e, 0, sizeof(*e));
    e->basic_state_available =
        get_player_data(&e->player_object, &e->player_data) &&
        get_player_camera(0, &e->camera) &&
        rd_u32(DISABLE_JOY_ADDR, &e->joy_disabled) &&
        rd_u8(e->player_data + PLAYER_CAMERA_MODE_OFFSET, &e->camera_mode);
    /* Free camera in the jump camera too (Project64JFG's default) */
    e->jump_camera_mode = e->basic_state_available && e->camera_mode == CAMERA_MODE_JUMP;
    e->free_jump_camera_allowed = e->jump_camera_mode;
    e->free_camera_blocked_by_jump = 0;
    e->constrained_state_available =
        e->basic_state_available &&
        get_normal_camera_state(e->player_data, &e->normal_camera, &e->mouse_camera_allowed);
    /* Keep the game's own camera wherever it is not the plain follow camera */
    e->free_camera_state_allowed =
        e->joy_disabled == 0 &&
        (e->free_jump_camera_allowed || (e->constrained_state_available && e->normal_camera));
    e->enable_free_orbit = e->free_camera_state_allowed && !aim;
}

/* Spends this frame's stick counts on the orbit yaw and the camera height and
 * writes them where the patched camera code reads them. */
static void apply_orbit(const struct jfg_eval* e, int32_t dx, int32_t dy, int aim)
{
    float height = 0.0f;

    if (!g_camera_patch_applied || !e->basic_state_available)
    {
        reset_orbit();
    }
    else
    {
        if (e->camera != g_orbit.tracked_camera || e->player_object != g_orbit.tracked_player_object)
        {
            reset_orbit();
            g_orbit.tracked_camera = e->camera;
            g_orbit.tracked_player_object = e->player_object;
            g_orbit.height_offset = 0.0f;
            g_orbit.orbit_yaw = 0;
        }

        if (!e->free_jump_camera_allowed &&
            (!e->constrained_state_available || e->joy_disabled != 0 || !e->mouse_camera_allowed))
        {
            g_orbit.override_active = 0;
            g_orbit.override_suspended = 0;
            g_orbit.orbit_yaw_initialized = 0;
            g_orbit.elevation_ready = 0;
        }
        else if (aim || e->joy_disabled != 0 ||
                 (!e->free_jump_camera_allowed && e->camera_mode != CAMERA_MODE_NORMAL))
        {
            if (g_orbit.override_active)
            {
                if (aim)
                    align_player_to_orbit(e->player_object, e->player_data);
                wr_s16(e->camera + CAMERA_PITCH_OFFSET, 0);
                wr_s16(e->player_data + PLAYER_CAMERA_YAW_OFFSET, 0);
                g_orbit.override_active = 0;
                g_orbit.override_suspended = 1;
                g_orbit.orbit_yaw_initialized = 0;
            }
            g_orbit.elevation_ready = 0;
        }
        else
        {
            int ready = 1;
            int16_t base_yaw = 0;
            g_orbit.override_suspended = 0;
            g_orbit.override_active = 1;
            if (!g_orbit.orbit_yaw_initialized)
            {
                ready = rd_s16(e->player_data + PLAYER_CAMERA_ORBIT_YAW_OFFSET, &g_orbit.orbit_yaw);
                g_orbit.orbit_yaw_initialized = ready;
            }

            if (ready && get_camera_base_yaw(e->player_object, e->player_data, &base_yaw))
            {
                g_orbit.orbit_yaw = (int16_t)((int32_t)g_orbit.orbit_yaw + dx * CAMERA_YAW_SENSITIVITY);
                wr_s16(e->player_data + PLAYER_CAMERA_ORBIT_YAW_OFFSET, g_orbit.orbit_yaw);
                wr_s16(e->player_data + PLAYER_CAMERA_YAW_OFFSET,
                       (int16_t)((int32_t)g_orbit.orbit_yaw - (int32_t)base_yaw));
                wr_u8(e->player_data + PLAYER_CAMERA_CENTER_OFFSET, 0);

                if (e->jump_camera_mode)
                {
                    g_orbit.height_offset = 0.0f;
                    g_orbit.elevation_ready = 0;
                }
                else
                {
                    g_orbit.height_offset = clamp_height(g_orbit.height_offset + (float)dy * CAMERA_HEIGHT_SENSITIVITY);
                    if (g_orbit.elevation_ready)
                        g_orbit.height_offset = clamp_elevation(0, e->player_object, e->camera, g_orbit.height_offset);
                    else
                        g_orbit.elevation_ready = 1;
                    height = g_orbit.height_offset;
                    wr_s16(e->camera + CAMERA_PITCH_OFFSET, 0);
                }
            }
        }
    }

    write_height_offset(0, height);
}

/* ------------------------------------------------------------------------ */
/* Pad                                                                       */
/* ------------------------------------------------------------------------ */

static void normalise_stick(float x, float y, float* ox, float* oy)
{
    float magnitude = sqrtf(x * x + y * y);
    float scale;
    if (magnitude <= STICK_DEAD_ZONE)
    {
        *ox = 0.0f;
        *oy = 0.0f;
        return;
    }
    scale = (magnitude - STICK_DEAD_ZONE) / (1.0f - STICK_DEAD_ZONE);
    if (scale > 1.0f) scale = 1.0f;
    *ox = x * scale / magnitude;
    *oy = y * scale / magnitude;
}

static int8_t stick_to_n64(float deflection)
{
    float value = deflection * (float)STICK_LIMIT;
    return (int8_t)(value >= 0.0f ? (int32_t)(value + 0.5f) : -(int32_t)(-value + 0.5f));
}

static void read_pad(struct jfg_pad* pad)
{
    pad->buttons = g_pad_buttons;
    normalise_stick(g_pad_lx, g_pad_ly, &pad->lx, &pad->ly);
    normalise_stick(g_pad_rx, g_pad_ry, &pad->rx, &pad->ry);
    pad->aim = (pad->buttons & JFG_PAD_L2) != 0 || g_pad_lt >= TRIGGER_THRESHOLD;
    pad->fire = (pad->buttons & JFG_PAD_R2) != 0 || g_pad_rt >= TRIGGER_THRESHOLD;
}

/* Y and X are weapon notches: a rising edge queues a single-poll impulse, from
 * whichever of the two sampling paths sees it first. */
static void queue_scroll(uint32_t buttons)
{
    const uint32_t pressed = buttons & ~g_prev_scroll_buttons;
    if (pressed & JFG_PAD_Y)
        g_queued_scroll = 1;
    else if (pressed & JFG_PAD_X)
        g_queued_scroll = -1;
    g_prev_scroll_buttons = buttons & (JFG_PAD_X | JFG_PAD_Y);
}

/* The game's control setup for player one, Normal or Expert, as nine button
 * masks: fire, next/previous weapon, jump, crouch, sidestep left/right and the
 * two buttons that walk while aiming. */
static int read_control_masks(uint16_t masks[9])
{
    uint8_t mode = 0;
    uint32_t table, i;
    if (!rd_u8(SELECTED_CONTROL_MODES, &mode))
        return 0;
    table = (mode & 1) ? CONTROL_MODE_EXPERT_TABLE : CONTROL_MODE_NORMAL_TABLE;
    for (i = 0; i < 9; i++)
    {
        uint32_t word;
        if (!rd_u32(table + 4 * i, &word) || word == 0 || (word & 0xFFFF0000u) != 0)
            return 0;
        masks[i] = (uint16_t)word;
    }
    return 1;
}

static uint32_t hw_to_plugin(uint16_t mask)
{
    return ((uint32_t)(mask & 0xFF00u) >> 8) | ((uint32_t)(mask & 0x00FFu) << 8);
}

/* ------------------------------------------------------------------------ */
/* Lifecycle                                                                 */
/* ------------------------------------------------------------------------ */

static void clear_host_state(void)
{
    memset(&g_orbit, 0, sizeof(g_orbit));
    g_camera_patch_applied = 0;
    g_scheme_active = 0;
    g_topdown_initialized = 0;
    g_topdown_counter = 0;
    g_topdown_hold = 0;
    g_queued_scroll = 0;
    g_prev_scroll_buttons = 0;
}

static void deactivate(void)
{
    int result = set_camera_code(0, 0);
    if (result == -1)
        return; /* camera code busy, retry next frame */
    clear_height_offsets();
    clear_host_state();
    g_installed = 0;
    g_maybe_dirty = 0;
    g_status = 0;
}

void jfg_camera_rom_started(void)
{
    g_rom_supported = tohl(ROM_HEADER.CRC1) == JFG_US_CRC1 && tohl(ROM_HEADER.CRC2) == JFG_US_CRC2;
    g_installed = 0;
    g_maybe_dirty = 0;
    g_mismatch_logged = 0;
    g_status = 0;
    clear_host_state();
    if (g_rom_supported)
        DebugMessage(M64MSG_INFO, "JFG camera: Jet Force Gemini (USA) detected, right stick camera available");
}

void jfg_camera_state_loaded(void)
{
    if (!g_rom_supported)
        return;
    /* The state carries whatever code was live when it was written. Drop our
     * bookkeeping and let the next frame line the code up with the settings. */
    clear_host_state();
    g_installed = 0;
    g_maybe_dirty = 1;
}

void jfg_camera_new_vi(void)
{
    struct jfg_pad pad;
    struct jfg_eval eval;
    uint32_t player_object = 0, player_data = 0, control_camera = 0, joy_disabled = 1, robot = 0;
    uint8_t paused = 1, camera_mode = 0;
    int ready, result, speed;
    int32_t dx = 0, dy = 0;

    if (!g_rom_supported)
        return;

    if (!g_cfg_enabled || !g_pad_seen || netplay_is_init())
    {
        if (g_installed || g_maybe_dirty)
            deactivate();
        g_scheme_active = 0;
        return;
    }

    if (g_status == 0)
        g_status = 1;

    read_pad(&pad);
    queue_scroll(pad.buttons);

    /* Nothing touches the game before a level is live and the player has control */
    ready = get_player_data(&player_object, &player_data) && player_object != 0 &&
            get_control_camera(&control_camera) &&
            rd_u32(DISABLE_JOY_ADDR, &joy_disabled) && joy_disabled == 0 &&
            rd_u8(PAUSE_MODE_ADDR, &paused) && paused == 0 &&
            rd_u8(player_data + PLAYER_CAMERA_MODE_OFFSET, &camera_mode);
    if (!ready)
    {
        g_scheme_active = 0;
        g_orbit.carry_x = g_orbit.carry_y = 0.0f;
        return;
    }

    rd_u32(ROBOT_MISSION_ADDR, &robot);
    g_scheme_active = robot == 0;

    /* Floyd's drone flights and the boss sections keep the game's camera: the
     * stock code goes back in and the right stick drives the game's reticle. */
    if (robot != 0 || camera_mode == CAMERA_MODE_BOSS_AIM)
    {
        g_orbit.orbit_yaw_initialized = 0;
        g_orbit.carry_x = g_orbit.carry_y = 0.0f;
        result = set_camera_code(1, 0);
        if (result != -1)
        {
            g_installed = result == 1;
            g_maybe_dirty = 0;
            g_camera_patch_applied = result == 1;
        }
        write_height_offset(0, 0.0f);
        return;
    }

    /* The right stick becomes camera counts per video frame, squared for fine
     * control near the centre, with the fraction carried to the next frame. */
    speed = g_cfg_speed;
    if (speed < 1) speed = 1;
    if (speed > 10) speed = 10;
    if (!pad.aim && (pad.rx != 0.0f || pad.ry != 0.0f))
    {
        const float rate = (float)speed * COUNTS_PER_SPEED;
        const float ry = g_cfg_invert_y ? -pad.ry : pad.ry;
        const float ax = pad.rx * fabsf(pad.rx) * rate + g_orbit.carry_x;
        const float ay = ry * fabsf(ry) * rate + g_orbit.carry_y;
        dx = (int32_t)ax;
        dy = (int32_t)ay;
        g_orbit.carry_x = ax - (float)dx;
        g_orbit.carry_y = ay - (float)dy;
    }
    else
    {
        g_orbit.carry_x = g_orbit.carry_y = 0.0f;
    }

    evaluate_orbit(pad.aim, &eval);
    result = set_camera_code(1, eval.enable_free_orbit);
    if (result == -1)
        return; /* the camera code is running: try again next frame */
    g_installed = result == 1;
    g_maybe_dirty = 0;
    g_camera_patch_applied = result == 1;
    if (!g_camera_patch_applied)
    {
        g_scheme_active = 0;
        return;
    }
    g_status = 2;
    apply_orbit(&eval, dx, dy, pad.aim);
}

void jfg_camera_filter_input(int control, uint32_t* value)
{
    struct jfg_pad pad;
    uint16_t masks[9];
    uint32_t player_object = 0, player_data = 0, out = 0;
    uint8_t camera_mode = 0;
    int scroll, left, right, forward, backward;
    int8_t x = 0, y = 0;

    if (control != 0 || !g_rom_supported || !g_scheme_active || !g_installed ||
        !g_cfg_enabled || !g_pad_seen || value == NULL)
        return;
    if (!read_control_masks(masks) || !get_player_data(&player_object, &player_data) ||
        !rd_u8(player_data + PLAYER_CAMERA_MODE_OFFSET, &camera_mode))
        return;

    read_pad(&pad);
    queue_scroll(pad.buttons);
    scroll = g_queued_scroll;
    g_queued_scroll = 0;

    left = pad.lx < -DIGITAL_THRESHOLD;
    right = pad.lx > DIGITAL_THRESHOLD;
    forward = pad.ly < -DIGITAL_THRESHOLD;
    backward = pad.ly > DIGITAL_THRESHOLD;

    /* Buttons, through the player's own control setup */
    if (pad.fire)                         out |= hw_to_plugin(masks[0]);
    if (scroll > 0)                       out |= hw_to_plugin(masks[1]);
    if (scroll < 0)                       out |= hw_to_plugin(masks[2]);
    if (pad.buttons & JFG_PAD_A)          out |= hw_to_plugin(masks[3]);
    if (pad.buttons & JFG_PAD_B)          out |= hw_to_plugin(masks[4]);
    if (pad.buttons & JFG_PAD_L1)         out |= hw_to_plugin(masks[5]);
    if (pad.buttons & JFG_PAD_R1)         out |= hw_to_plugin(masks[6]);
    if (pad.buttons & JFG_PAD_START)      out |= hw_to_plugin(N64_START);
    if (pad.buttons & JFG_PAD_DUP)        out |= hw_to_plugin(N64_DUP);
    if (pad.buttons & JFG_PAD_DDOWN)      out |= hw_to_plugin(N64_DDOWN);
    if (pad.buttons & JFG_PAD_DLEFT)      out |= hw_to_plugin(N64_DLEFT);
    if (pad.buttons & JFG_PAD_DRIGHT)     out |= hw_to_plugin(N64_DRIGHT);

    if (camera_mode == CAMERA_MODE_BOSS_AIM)
    {
        /* The game aims the boss reticle from the stick and runs the player
         * along the rail on the sidestep buttons: right stick on the stick,
         * left stick on the sidesteps. */
        if (left)  out |= hw_to_plugin(masks[5]);
        if (right) out |= hw_to_plugin(masks[6]);
        x = stick_to_n64(pad.rx);
        y = stick_to_n64(pad.ry);
    }
    else if (pad.aim)
    {
        /* The game's own aim: the right stick moves the reticle and turns the
         * view once it reaches the edge; the left stick walks on the buttons
         * the control setup gives the aim. */
        out |= hw_to_plugin(N64_R);
        if (forward)  out |= hw_to_plugin(masks[7]);
        if (backward) out |= hw_to_plugin(masks[8]);
        if (left)     out |= hw_to_plugin(masks[5]);
        if (right)    out |= hw_to_plugin(masks[6]);
        x = stick_to_n64(pad.rx);
        y = stick_to_n64(pad.ry);
    }
    else if (camera_mode == CAMERA_MODE_CROUCH || camera_mode == CAMERA_MODE_PRONE)
    {
        /* Crouched or prone, left and right shuffle sideways */
        if (left)  out |= hw_to_plugin(masks[5]);
        if (right) out |= hw_to_plugin(masks[6]);
        y = stick_to_n64(-pad.ly);
    }
    else
    {
        x = stick_to_n64(pad.lx);
        y = stick_to_n64(-pad.ly);
    }

    out |= ((uint32_t)(uint8_t)x) << 16;
    out |= ((uint32_t)(uint8_t)y) << 24;
    *value = out;
}

/* ------------------------------------------------------------------------ */
/* Frontend                                                                  */
/* ------------------------------------------------------------------------ */

EXPORT void CALL JfgCameraConfigure(int enabled, int speed, int invertY)
{
    g_cfg_enabled = enabled != 0;
    g_cfg_speed = speed;
    g_cfg_invert_y = invertY != 0;
}

EXPORT void CALL JfgCameraSetPad(unsigned int buttons, float lx, float ly, float rx, float ry, float lt, float rt)
{
    g_pad_buttons = buttons;
    g_pad_lx = lx;
    g_pad_ly = ly;
    g_pad_rx = rx;
    g_pad_ry = ry;
    g_pad_lt = lt;
    g_pad_rt = rt;
    g_pad_seen = 1;
}

EXPORT int CALL JfgCameraStatus(void)
{
    return g_rom_supported && g_cfg_enabled ? g_status : 0;
}
