// Minimal farmhash implementation for Fingerprint64
// Based on FarmHash by Google (MIT license)
#include <cstdint>
#include <cstring>

namespace util {

static inline uint64_t Fetch64(const char* p) {
    uint64_t result;
    memcpy(&result, p, sizeof(result));
    return result;
}

static inline uint32_t Fetch32(const char* p) {
    uint32_t result;
    memcpy(&result, p, sizeof(result));
    return result;
}

static inline uint64_t Rotate(uint64_t val, int shift) {
    return shift == 0 ? val : ((val >> shift) | (val << (64 - shift)));
}

static inline uint64_t ShiftMix(uint64_t val) {
    return val ^ (val >> 47);
}

static inline uint64_t HashLen16(uint64_t u, uint64_t v) {
    const uint64_t kMul = 0x9ddfea08eb382d69ULL;
    uint64_t a = (u ^ v) * kMul;
    a ^= (a >> 47);
    uint64_t b = (v ^ a) * kMul;
    b ^= (b >> 44);
    b *= kMul;
    b ^= (b >> 41);
    b *= kMul;
    return b;
}

static inline uint64_t HashLen0to16(const char* s, size_t len) {
    if (len >= 8) {
        uint64_t mul = 0x9ddfea08eb382d69ULL + len * 2;
        uint64_t a = Fetch64(s) + 0x9ddfea08eb382d69ULL;
        uint64_t b = Fetch64(s + len - 8);
        uint64_t c = Rotate(b, 37) * mul + a;
        uint64_t d = (Rotate(a, 25) + b) * mul;
        return HashLen16(c, d);
    }
    if (len >= 4) {
        uint64_t mul = 0x9ddfea08eb382d69ULL + len * 2;
        uint64_t a = Fetch32(s);
        return HashLen16(len + (a << 3), Fetch32(s + len - 4));
    }
    if (len > 0) {
        uint8_t a = (uint8_t)s[0];
        uint8_t b = (uint8_t)s[len >> 1];
        uint8_t c = (uint8_t)s[len - 1];
        uint32_t y = (uint32_t)a + ((uint32_t)b << 8);
        uint32_t z = (uint32_t)len + ((uint32_t)c << 2);
        return ShiftMix(y * 0x9ddfea08eb382d69ULL ^ z * 0xc4ceb9fe1a85ec53ULL) * 0x9ddfea08eb382d69ULL;
    }
    return 0x9ddfea08eb382d69ULL;
}

static inline uint64_t HashLen17to32(const char* s, size_t len) {
    uint64_t mul = 0x9ddfea08eb382d69ULL + len * 2;
    uint64_t a = Fetch64(s) * 0x9ddfea08eb382d69ULL;
    uint64_t b = Fetch64(s + 8);
    uint64_t c = Fetch64(s + len - 8) * mul;
    uint64_t d = Fetch64(s + len - 16) * 0x9ddfea08eb382d69ULL;
    return HashLen16(Rotate(a + b, 43) + Rotate(c, 30) + d, a + Rotate(b + 0x9ddfea08eb382d69ULL, 18) + c);
}

static inline uint64_t HashLen33to64(const char* s, size_t len) {
    uint64_t mul = 0x9ddfea08eb382d69ULL + len * 2;
    uint64_t a = Fetch64(s) * 0x9ddfea08eb382d69ULL;
    uint64_t b = Fetch64(s + 8);
    uint64_t c = Fetch64(s + len - 8) * mul;
    uint64_t d = Fetch64(s + len - 16) * 0x9ddfea08eb382d69ULL;
    uint64_t y = Rotate(a + b, 43) + Rotate(c, 30) + d;
    uint64_t z = HashLen16(y, a + Rotate(b + 0x9ddfea08eb382d69ULL, 18) + c);
    uint64_t e = Fetch64(s + 16) * mul;
    uint64_t f = Fetch64(s + 24);
    uint64_t g = (y + Fetch64(s + len - 32)) * mul;
    uint64_t h = (z + Fetch64(s + len - 24)) * mul;
    return HashLen16(Rotate(e + f, 43) + Rotate(g, 30) + h, e + Rotate(f + a, 18) + g);
}

uint64_t Fingerprint64(const char* s, size_t len) {
    if (len <= 64) {
        if (len <= 32) {
            if (len <= 16) {
                return HashLen0to16(s, len);
            } else {
                return HashLen17to32(s, len);
            }
        } else {
            return HashLen33to64(s, len);
        }
    }
    // For longer strings, use a simplified approach
    uint64_t seed = 0x9ddfea08eb382d69ULL;
    const char* end = s + len;
    while (s + 64 <= end) {
        uint64_t a = Fetch64(s) * 0x9ddfea08eb382d69ULL;
        uint64_t b = Fetch64(s + 8);
        uint64_t c = Fetch64(s + 16);
        uint64_t d = Fetch64(s + 24);
        seed = HashLen16(seed + a + b, c + d);
        s += 32;
    }
    if (s < end) {
        size_t remaining = (size_t)(end - s);
        seed += HashLen0to16(s, remaining);
    }
    return seed;
}

}  // namespace util
