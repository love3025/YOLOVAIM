// touch_core.h — Shared touch injection API
// Used by both JNI (Shizuku) and root_daemon (su)

#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Dedicated slots for virtual/trigger fingers on device 0
#define TOUCH_VIRTUAL_SLOT  8
#define TOUCH_TRIGGER_SLOT  9
#define TOUCH_VIRTUAL_ID    1000
#define TOUCH_TRIGGER_ID    2000

// Lifecycle
bool touch_init(int screenW, int screenH);
// Reader-only variant for the stealth (KPM) path: opens the real panel
// WITHOUT EVIOCGRAB and never creates a uinput device — real fingers keep
// flowing to the game and injection goes through the kernel KPM channel.
// g_devices[0]/zone detection/fire-state bookkeeping work exactly as in
// touch_init(); upload() stays a no-op because g_outputFd stays 0.
bool touch_init_reader_only(int screenW, int screenH);
void touch_close(void);
bool touch_is_initialized(void);
int  touch_get_output_fd(void);

// Reader threads (for zone detection)
void touch_start_readers(void);
void touch_stop_readers(void);

// Configuration
void touch_set_screen_params(int w, int h, bool landscape);

// Injection (screen coordinates — rotation handled internally)
void touch_down(int slot, int id, int screenX, int screenY);
void touch_move(int slot, int screenX, int screenY);
void touch_up(int slot);

// Zone configuration (screen coordinates)
void touch_set_trigger_zone(int l, int t, int r, int b);
void touch_set_fire_zone(int l, int t, int r, int b);
void touch_set_joystick_zone(int l, int t, int r, int b);

// Zone queries
bool touch_is_finger_in_trigger_zone(void);
bool touch_is_finger_in_fire_zone(void);
// 一次调用同时取回开火区电平与自上次调用以来的点击(上升沿)次数并清零：
//   bit0 = 当前是否有手指在开火区；bit1.. = 点击次数
int  touch_consume_fire_state(void);
bool touch_is_finger_in_joystick_zone(void);

// Lift physical finger in joystick zone
bool touch_lift_joystick_finger(void);

// Panel slots of real fingers currently inside the joystick zone (stealth
// path: caller injects an Up on each slot via the KPM channel). Returns the
// number of slots written to outSlots.
int  touch_get_joystick_finger_slots(int* outSlots, int maxSlots);

#ifdef __cplusplus
}
#endif
