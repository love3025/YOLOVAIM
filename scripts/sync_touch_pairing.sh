#!/bin/sh
# sync_touch_pairing.sh — 把 app 侧的 touch_pairing.h 渲染成「盐已展开」的
# KPM 侧副本,写进 kpm-src/kpms/inputprobe/。
#
# 通道密钥 = FNV-1a64(包名 + 盐)。app 侧的盐由构建期从 local.properties
# 注入(不进 git);KPM 侧是独立构建(kpm-src 在本仓库外),需要一份盐已
# 展开的实体头文件。本脚本生成的文件与 app 侧在「包名/盐/通道号」三个
# 决定密钥的量上完全一致 —— 一字不差的字节一致做不到(app 侧保留 #ifndef
# 占位结构),所以脚本同时校验这三项。
#
# 用法:
#   scripts/sync_touch_pairing.sh [-k /path/to/kpm-src] [-p <salt>]
#     -k  kpm-src 根目录(默认:../无痕触摸源码/kpm-src,相对本仓库)
#     -p  直接给盐(默认依次读 $STEALTH_PAIR_SALT、local.properties 的
#         stealth.pairSalt)
#
# 生成后重编 inputprobe.kpm(见 无痕触摸源码/README.md),刷入设备。

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
SRC="$REPO_ROOT/app/src/main/cpp/src/injection/stealth/touch_pairing.h"

KPM_SRC="$REPO_ROOT/../无痕触摸源码/kpm-src"
SALT="${STEALTH_PAIR_SALT:-}"

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
while [ $# -gt 0 ]; do
    case "$1" in
        -k) KPM_SRC=$2; shift 2 ;;
        -p) SALT=$2; shift 2 ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1" >&2; usage ;;
    esac
done

# 盐:命令行 > 环境变量 > local.properties
if [ -z "$SALT" ]; then
    LP="$REPO_ROOT/local.properties"
    if [ -f "$LP" ]; then
        SALT=$(sed -n 's/^stealth.pairSalt=//p' "$LP" | head -n1)
    fi
fi

if [ -z "$SALT" ]; then
    echo "错误: 找不到盐。在 local.properties 里配置 stealth.pairSalt=...,或用 -p <salt> 传入。" >&2
    exit 1
fi
case "$SALT" in
    PLACEHOLDER*)
        echo "错误: 盐还是占位符,先在 local.properties 里配真实盐。" >&2
        exit 1
        ;;
esac

DST="$KPM_SRC/kpms/inputprobe/touch_pairing.h"
if [ ! -d "$(dirname "$DST")" ]; then
    echo "错误: 目标目录不存在: $(dirname "$DST") — 用 -k 指向 kpm-src 根目录。" >&2
    exit 1
fi

# 渲染:去掉 #ifndef 占位包裹,把占位 #define 换成真实盐
awk -v salt="$SALT" '
    /^#ifndef TOUCH_PAIR_SALT$/ { inblock=1; next }
    inblock && /^#define TOUCH_PAIR_SALT/ { print "#define TOUCH_PAIR_SALT      \"" salt "\""; next }
    inblock && /^#endif$/ { inblock=0; next }
    { print }
' "$SRC" > "$DST.tmp"

# 生成标记 + 覆盖
{
    echo "/* !! 由 scripts/sync_touch_pairing.sh 生成,不要手改 — 重跑脚本覆盖。"
    echo " * 源头: YOLOVAIM/app/src/main/cpp/src/injection/stealth/touch_pairing.h"
    echo " * + local.properties 的 stealth.pairSalt。改包名/通道号请改源头再同步。 */"
    cat "$DST.tmp"
} > "$DST"
rm -f "$DST.tmp"

# 校验决定密钥的三个量两侧一致
for key in TOUCH_TARGET_PACKAGE TOUCH_SC_NR; do
    src_v=$(grep "^#define $key" "$SRC" | head -n1)
    dst_v=$(grep "^#define $key" "$DST" | head -n1)
    if [ "$src_v" != "$dst_v" ]; then
        echo "错误: $key 两侧不一致:" >&2
        echo "  app 侧: $src_v" >&2
        echo "  kpm 侧: $dst_v" >&2
        exit 1
    fi
done
if ! grep -q "^#define TOUCH_PAIR_SALT      \"$SALT\"$" "$DST"; then
    echo "错误: 盐展开结果校验失败,请检查 $DST。" >&2
    exit 1
fi

echo "已生成: $DST"
echo "  包名:   $(grep '^#define TOUCH_TARGET_PACKAGE' "$DST")"
echo "  通道号: $(grep '^#define TOUCH_SC_NR' "$DST")"
echo "下一步: 重编 inputprobe.kpm 并刷入设备(无痕触摸源码/README.md),"
echo "        再用同一份 local.properties 的盐构建 app。"
