// inputmgr_core.h — InputManager evdev reader
// Reads physical touch events from evdev, tracks pointers, detects zones.
// No uinput device creation — pointers returned to Java for MotionEvent injection.
// Matches reference implementation: TouchMergerUserService.kt

#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Constants matching reference
#define INPUTMGR_MAX_SLOTS 16
#define INPUTMGR_MAX_FINGERS 10
#define INPUTMGR_SYNTHETIC_ID_START 100000

// Physical pointer info (returned to Java)
struct PhysicalPointer {
    int id;        // tracking ID (kernel or synthetic)
    float x;       // screen X coordinate (after rotation)
    float y;       // screen Y coordinate (after rotation)
    float pressure; // always 1.0f
};

// Lifecycle
bool inputmgr_init(int screenW, int screenH);
void inputmgr_close(void);
bool inputmgr_is_initialized(void);

// Grab control (delayed — call on first inject)
void inputmgr_grab(void);
void inputmgr_ungrab(void);
bool inputmgr_is_grabbed(void);

// Blocking poll: waits for evdev events, processes them, updates pointer state.
// Returns: 1 if pointers changed (SYN_REPORT received), 0 on timeout, -1 on error.
// timeoutMs: max wait time in milliseconds (-1 = infinite).
int inputmgr_poll_and_update(int timeoutMs);

// Read current physical pointers into caller-provided buffer
// Returns number of active pointers written
int inputmgr_read_pointers(PhysicalPointer* buf, int maxCount);

// Device info
int inputmgr_get_device_id(void);
int inputmgr_get_max_x(void);
int inputmgr_get_max_y(void);
bool inputmgr_has_slot_support(void);

// Configuration
void inputmgr_set_screen_params(int w, int h, int rotation);

// Zone configuration (screen coordinates)
void inputmgr_set_trigger_zone(int l, int t, int r, int b);
void inputmgr_set_fire_zone(int l, int t, int r, int b);
void inputmgr_set_joystick_zone(int l, int t, int r, int b);

// Zone queries
bool inputmgr_is_finger_in_trigger_zone(void);
bool inputmgr_is_finger_in_fire_zone(void);
bool inputmgr_is_finger_in_joystick_zone(void);

// Lift physical finger in joystick zone
bool inputmgr_lift_joystick_finger(void);

#ifdef __cplusplus
}
#endif
