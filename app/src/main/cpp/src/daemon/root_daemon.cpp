/*
 * root_daemon.cpp — Thin stdin/stdout command parser over touch_core.
 * Runs under `su`, communicates via text protocol.
 * All uinput logic lives in touch_core.cpp.
 *
 * Protocol:
 *   Commands (stdin, one per line):
 *     SET_RESOLUTION <screenW> <screenH>
 *     SET_DEVICE_RESOLUTION <devW> <devH>
 *     SET_ORIENTATION <1|0>          (1=landscape, 0=portrait)
 *     OPEN_UINPUT
 *     CLOSE_UINPUT
 *     START_GETEVENT
 *     STOP_GETEVENT
 *     DOWN <x> <y>
 *     MOVE <x> <y>
 *     UP
 *     TRIGGER_DOWN <x> <y>
 *     TRIGGER_UP
 *     SET_TRIGGER_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_ZONE
 *     SET_FIRE_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_FIRE_ZONE
 *     GET_FIRE_STATE
 *     SET_JOYSTICK_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_JOYSTICK_ZONE
 *     LIFT_JOYSTICK_FINGER
 *     KEEP_ALIVE
 *     DESTROY
 *
 *   Responses (stdout, one per line):
 *     OK
 *     OK:<value>
 *     ERR:<message>
 *
 *   A command may be prefixed with '!' to suppress its response line entirely
 *   (fire-and-forget). The client uses this for the void, per-frame injection
 *   commands (MOVE / UP / TRIGGER_DOWN / TRIGGER_UP) so the inference thread
 *   can hand off an injection without blocking on a round-trip through this
 *   process's scheduling. Ordering is unaffected: stdin is a single serial
 *   stream consumed by one thread, so a '!' command is still executed strictly
 *   before whatever follows it. Commands that return a value are never sent
 *   this way, so the client's read stream stays in lockstep.
 *
 *   All debug/log output goes to stderr only.
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>
#include "touch_core.h"

// Screen params (set via commands before OPEN_UINPUT)
static int g_screen_w = 0;
static int g_screen_h = 0;
static volatile int g_running = 1;

// Set per-command by the '!' prefix; suppresses this command's response line.
static int g_quiet = 0;

static void reply(const char* s) {
    if (!g_quiet) puts(s);
}

static void reply_int(int v) {
    if (!g_quiet) printf("OK:%d\n", v);
}

// =========================================================================
// Command handler
// =========================================================================

static void handle_command(const char* cmd) {
    char buf[1024];
    strncpy(buf, cmd, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    char* nl = strchr(buf, '\n');
    if (nl) *nl = '\0';
    char* cr = strchr(buf, '\r');
    if (cr) *cr = '\0';

    // Fire-and-forget prefix. Strip it and remember to stay silent.
    g_quiet = 0;
    if (buf[0] == '!') {
        g_quiet = 1;
        memmove(buf, buf + 1, strlen(buf));  // includes the terminator
    }

    if (strncmp(buf, "SET_RESOLUTION ", 15) == 0) {
        int w, h;
        if (sscanf(buf + 15, "%d %d", &w, &h) == 2 && w > 0 && h > 0) {
            g_screen_w = w;
            g_screen_h = h;
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "SET_DEVICE_RESOLUTION ", 22) == 0) {
        // Device resolution is auto-detected by touch_core, ignore
        reply("OK");
    }
    else if (strncmp(buf, "SET_ORIENTATION ", 16) == 0) {
        int landscape = atoi(buf + 16);
        touch_set_screen_params(g_screen_w, g_screen_h, landscape != 0);
        reply("OK");
    }
    else if (strcmp(buf, "OPEN_UINPUT") == 0) {
        if (touch_init(g_screen_w, g_screen_h)) {
            int fd = touch_get_output_fd();
            reply_int(fd);
        } else {
            reply("ERR:open failed");
        }
    }
    else if (strcmp(buf, "CLOSE_UINPUT") == 0) {
        touch_close();
        reply("OK");
    }
    else if (strcmp(buf, "START_GETEVENT") == 0) {
        touch_start_readers();
        reply("OK");
    }
    else if (strcmp(buf, "STOP_GETEVENT") == 0) {
        touch_stop_readers();
        reply("OK");
    }
    else if (strncmp(buf, "DOWN ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            touch_down(TOUCH_VIRTUAL_SLOT, TOUCH_VIRTUAL_ID, x, y);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "MOVE ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            touch_move(TOUCH_VIRTUAL_SLOT, x, y);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "UP") == 0) {
        touch_up(TOUCH_VIRTUAL_SLOT);
        reply("OK");
    }
    else if (strncmp(buf, "TRIGGER_DOWN ", 13) == 0) {
        int x, y;
        if (sscanf(buf + 13, "%d %d", &x, &y) == 2) {
            touch_down(TOUCH_TRIGGER_SLOT, TOUCH_TRIGGER_ID, x, y);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "TRIGGER_UP") == 0) {
        touch_up(TOUCH_TRIGGER_SLOT);
        reply("OK");
    }
    else if (strncmp(buf, "SET_TRIGGER_ZONE ", 17) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 17, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_trigger_zone(l, t, r, b);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_ZONE") == 0) {
        reply_int(touch_is_finger_in_trigger_zone() ? 1 : 0);
    }
    else if (strncmp(buf, "SET_FIRE_ZONE ", 14) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 14, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_fire_zone(l, t, r, b);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_FIRE_ZONE") == 0) {
        reply_int(touch_is_finger_in_fire_zone() ? 1 : 0);
    }
    else if (strcmp(buf, "GET_FIRE_STATE") == 0) {
        reply_int(touch_consume_fire_state());
    }
    else if (strncmp(buf, "SET_JOYSTICK_ZONE ", 18) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 18, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_joystick_zone(l, t, r, b);
            reply("OK");
        } else {
            reply("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_JOYSTICK_ZONE") == 0) {
        reply_int(touch_is_finger_in_joystick_zone() ? 1 : 0);
    }
    else if (strcmp(buf, "LIFT_JOYSTICK_FINGER") == 0) {
        reply_int(touch_lift_joystick_finger() ? 1 : 0);
    }
    else if (strcmp(buf, "KEEP_ALIVE") == 0) {
        reply("OK");
    }
    else if (strcmp(buf, "DESTROY") == 0) {
        touch_close();
        reply("OK");
        g_running = 0;
    }
    else if (strlen(buf) == 0) {
        // Ignore empty lines
    }
    else {
        fprintf(stderr, "Unknown command: %s\n", buf);
        reply("ERR:unknown command");
    }
    if (!g_quiet) fflush(stdout);
}

// =========================================================================
// Main
// =========================================================================

int main() {
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IOLBF, 0);

    puts("READY");
    fflush(stdout);

    char line[1024];
    while (g_running) {
        if (fgets(line, sizeof(line), stdin) == NULL) {
            break;
        }
        handle_command(line);
    }

    touch_close();
    return 0;
}
