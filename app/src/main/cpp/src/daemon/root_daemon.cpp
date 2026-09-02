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
 *   HUD (anti-capture overlay, see src/hud/):
 *     HUD_ON                       (replies OK / ERR:hud <rc>; one-shot setup)
 *     HUD_OFF
 *     HUD_TOGGLE <captureRange|fov|box|centerDot|inferInfo> <0|1>
 *     HUD_CHECK_ON / HUD_CHECK_OFF   self-check pattern (magenta dot+ring)
 *     HUD_GEO <w> <h>              capture-space size (MediaProjection frame)
 *     HUD_FOV <r>
 *     HUD_RANGE <r>
 *     HUD_BOXES <n> <x1 y1 x2 y2>...   (n <= 16, int coords)
 *     HUD_TEXT_MASK <w> <h> <fg-hex> <bg-hex> <nbytes>
 *                                  header line, then exactly <nbytes> raw
 *                                  bytes follow on stdin: an 8-bit alpha
 *                                  coverage mask, packed w*h, row-major.
 *                                  fg/bg are Android 0xAARRGGBB; the daemon
 *                                  composites fg over bg per coverage byte.
 *                                  Replaces the old HUD_TEXT_BMP/ROW/END RLE
 *                                  protocol, which degenerated to thousands
 *                                  of runs per frame on anti-aliased text
 *                                  (measured 5558 runs / 270 IPC lines for a
 *                                  950x52 label) and was a main cause of the
 *                                  stutter seen with anti-capture enabled.
 *     All HUD_* except HUD_ON are meant to be sent with the '!' prefix:
 *     they never produce output and never block the sender.
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
#include <stdint.h>
#include <signal.h>
#include "touch_core.h"
#include "../hud/hud_renderer.h"

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
    char buf[4096];
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
    else if (strcmp(buf, "HUD_ON") == 0) {
        // Blocking setup command (rare, not on the inference hot path), so it
        // keeps its reply: the client uses OK / ERR to decide whether the
        // native HUD is viable or the fallback renderer must stay up.
        int rc = hud::renderer_start();
        if (0 == rc) {
            reply("OK");
        } else {
            char msg[48];
            snprintf(msg, sizeof(msg), "ERR:hud %d", rc);
            reply(msg);
        }
    }
    else if (strcmp(buf, "HUD_OFF") == 0) {
        hud::renderer_stop();
        reply("OK");
    }
    else if (strncmp(buf, "HUD_TOGGLE ", 11) == 0) {
        char what[32] = {0};
        int on = 0;
        if (sscanf(buf + 11, "%31s %d", what, &on) == 2)
            hud::set_toggle(what, on);
    }
    else if (strcmp(buf, "HUD_CHECK_ON") == 0) {
        hud::set_check_mode(1);
    }
    else if (strcmp(buf, "HUD_CHECK_OFF") == 0) {
        hud::set_check_mode(0);
    }
    else if (strncmp(buf, "HUD_GEO ", 8) == 0) {
        int w, h;
        if (sscanf(buf + 8, "%d %d", &w, &h) == 2)
            hud::set_geo(w, h);
    }
    else if (strncmp(buf, "HUD_FOV ", 8) == 0) {
        hud::set_fov(atoi(buf + 8));
    }
    else if (strncmp(buf, "HUD_RANGE ", 10) == 0) {
        hud::set_range(atoi(buf + 10));
    }
    else if (strncmp(buf, "HUD_BOXES ", 10) == 0) {
        char *p = buf + 10;
        char *end = NULL;
        long n = strtol(p, &end, 10);
        p = end;
        if (n < 0) n = 0;
        if (n > 16) n = 16;
        int coords[64];
        for (int i = 0; i < n * 4 && i < 64; i++) {
            long v = strtol(p, &end, 10);
            p = end;
            coords[i] = (int)v;
        }
        hud::set_boxes((int)n, coords);
    }
    else if (strncmp(buf, "HUD_TEXT_MASK ", 14) == 0) {
        // 头部一行 + 紧跟 nbytes 个原始字节(8bit 覆盖率掩码)。
        // 用 fread 而不是 read(0,...):stdin 是 FILE*,上面的 fgets 可能
        // 已经把掩码的前几个字节读进 stdio 缓冲了,只有 fread 能接着取。
        int w = 0, h = 0;
        unsigned int fg = 0, bg = 0;
        long nbytes = -1;
        if (sscanf(buf + 14, "%d %d %x %x %ld", &w, &h, &fg, &bg, &nbytes) != 5 ||
            nbytes < 0) {
            fprintf(stderr, "HUD_TEXT_MASK: bad header\n");
            return; // 一个二进制字节都没读,IPC 流仍然对齐
        }
        // 不管头部合法与否,nbytes 个字节都必须吃掉:少读一个字节,后面的
        // 命令就会从掩码数据中间开始解析,整条 IPC 流永久错位。
        static unsigned char *mbuf = NULL;
        static size_t mcap = 0;
        const long MAX_MASK = 4096L * 512L;
        int ok = (nbytes <= MAX_MASK);
        if (ok && (size_t)nbytes > mcap) {
            unsigned char *nb = (unsigned char *)realloc(mbuf, (size_t)nbytes);
            if (NULL == nb) {
                ok = 0;
            } else {
                mbuf = nb;
                mcap = (size_t)nbytes;
            }
        }
        if (ok) {
            size_t got = 0;
            while (got < (size_t)nbytes) {
                size_t r = fread(mbuf + got, 1, (size_t)nbytes - got, stdin);
                if (0 == r) break; // EOF / 读错误
                got += r;
            }
            if (got == (size_t)nbytes)
                hud::set_text_mask(w, h, (uint32_t)fg, (uint32_t)bg, mbuf, got);
            else
                fprintf(stderr, "HUD_TEXT_MASK: short read %zu/%ld\n", got, nbytes);
        } else {
            char sink[4096];
            long left = nbytes;
            while (left > 0) {
                size_t want = (left < (long)sizeof(sink)) ? (size_t)left : sizeof(sink);
                size_t r = fread(sink, 1, want, stdin);
                if (0 == r) break;
                left -= (long)r;
            }
            fprintf(stderr, "HUD_TEXT_MASK: rejected %ld bytes (cap %ld)\n", nbytes, MAX_MASK);
        }
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

    // 4096: HUD_BOXES lines can reach a few hundred chars (16 boxes x 4
    // coords). HUD_TEXT_MASK's payload is NOT read here — its header line is
    // short and the raw bytes that follow are consumed by fread inside the
    // handler, so this buffer never has to hold a bitmap.
    // A line longer than the buffer would split mid-command and desync the
    // client's reply stream, so keep this comfortably above what the Kotlin
    // side's chunking (max ~700 chars/line) can produce.
    char line[4096];
    while (g_running) {
        if (fgets(line, sizeof(line), stdin) == NULL) {
            break;
        }
        handle_command(line);
    }

    hud::renderer_stop();
    touch_close();
    return 0;
}
