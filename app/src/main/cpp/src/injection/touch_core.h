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

#ifdef __cplusplus
}
#endif
