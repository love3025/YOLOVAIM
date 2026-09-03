#!/bin/sh
# sync_touch_pairing.sh — 把 app 侧的 touch_pairing.h 拷到 KPM 侧,保持一字不差。
#
# 通道密钥 = FNV-1a64(包名 + 盐)。盐直接写在源码里(见 touch_pairing.h
# 头注释的决策说明),所以两侧副本就是同一份文件 —— 直接 cp,再校验
# 包名/盐/通道号三个决定密钥的量一致(防手滑只改了一边)。
#
# 用法:
#   scripts/sync_touch_pairing.sh [-k /path/to/kpm-src]
#     -k  kpm-src 根目录(默认:../无痕触摸源码/kpm-src,相对本仓库)
#
# 拷完重编 inputprobe.kpm(无痕触摸源码/README.md),刷入设备。

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
SRC="$REPO_ROOT/app/src/main/cpp/src/injection/stealth/touch_pairing.h"

KPM_SRC="$REPO_ROOT/../无痕触摸源码/kpm-src"

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
while [ $# -gt 0 ]; do
    case "$1" in
        -k) KPM_SRC=$2; shift 2 ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1" >&2; usage ;;
    esac
done

DST="$KPM_SRC/kpms/inputprobe/touch_pairing.h"
if [ ! -d "$(dirname "$DST")" ]; then
    echo "错误: 目标目录不存在: $(dirname "$DST") — 用 -k 指向 kpm-src 根目录。" >&2
    exit 1
fi

cp "$SRC" "$DST"

# 校验:拷贝必须一字不差,且三个决定密钥的量非空
if ! cmp -s "$SRC" "$DST"; then
    echo "错误: 拷贝后内容不一致(不应该发生)。" >&2
    exit 1
fi
for key in TOUCH_TARGET_PACKAGE TOUCH_PAIR_SALT TOUCH_SC_NR; do
    v=$(grep "^#define $key" "$DST" | head -n1)
    case "$v" in
        *"PLACEHOLDER"*|*"CHANGE-ME"*|*"example.yourapp"*)
            echo "错误: $key 还是占位值: $v" >&2; exit 1 ;;
        "") echo "错误: $key 缺失" >&2; exit 1 ;;
    esac
done

echo "已同步: $DST (与 app 侧一字不差)"
grep -E "^#define (TOUCH_TARGET_PACKAGE|TOUCH_PAIR_SALT|TOUCH_SC_NR)" "$DST"
echo "下一步: 重编 inputprobe.kpm 并刷入设备(无痕触摸源码/README.md)。"
