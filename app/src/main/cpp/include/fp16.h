// Minimal fp16 stub for neuron delegate compilation
#ifndef FP16_H_
#define FP16_H_

#include <stdint.h>

static inline float fp16_ieee_to_fp32_value(uint16_t h) {
    uint32_t sign = (h >> 15) & 1;
    uint32_t expo = (h >> 10) & 0x1f;
    uint32_t mant = h & 0x3ff;
    float f;
    if (expo == 0) {
        if (mant == 0) {
            f = 0.0f;
        } else {
            f = (float)mant / 1024.0f;
            if (sign) f = -f;
        }
    } else if (expo == 31) {
        f = (mant == 0) ? (sign ? -1e30f : 1e30f) : 0.0f / 0.0f;
    } else {
        uint32_t bits = (sign << 31) | ((expo + 112) << 23) | (mant << 13);
        union { uint32_t u; float f; } conv;
        conv.u = bits;
        f = conv.f;
    }
    return f;
}

static inline uint16_t fp16_ieee_from_fp32_value(float f) {
    uint32_t bits;
    union { float f; uint32_t u; } conv;
    conv.f = f;
    bits = conv.u;
    uint32_t sign = (bits >> 31) & 1;
    uint32_t expo = (bits >> 23) & 0xff;
    uint32_t mant = bits & 0x7fffff;
    uint16_t h;
    if (expo == 0) {
        h = (uint16_t)(sign << 15);
    } else if (expo == 0xff) {
        h = (uint16_t)((sign << 15) | 0x7c00 | (mant ? 0x200 : 0));
    } else {
        int e = (int)expo - 127 + 15;
        if (e >= 31) {
            h = (uint16_t)((sign << 15) | 0x7c00);
        } else if (e <= 0) {
            h = (uint16_t)((sign << 15) | ((mant | 0x800000) >> (1 - e + 10)));
        } else {
            h = (uint16_t)((sign << 15) | ((uint32_t)e << 10) | (mant >> 13));
        }
    }
    return h;
}

#endif  // FP16_H_
