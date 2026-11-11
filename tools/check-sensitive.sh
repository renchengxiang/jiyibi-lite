#!/bin/bash
# check-sensitive.sh —— 发布前合规自查（NFR-002）
# 用法：bash tools/check-sensitive.sh   （任何命中即返回非零退出码）
#
# 红线：公开物料里不得出现目标应用的名称、包名、域名、证书/APK 指纹。
# 只在仓库内文件上检查；私域材料（原始逆向报告）不放在本仓库。
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR" || exit 1

PATTERNS=(
  '一木'
  'yimubill'
  'yimuapp'
  'wangc'
  'com\.wangc'
  'c200a8a3'
  'ee615cd8'
  '一木记账'
)

HITS=0
for p in "${PATTERNS[@]}"; do
  # 排除 .git / build / 二进制
  out=$(grep -rInE "$p" \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=model-cn \
        --exclude='*.png' --exclude='*.apk' --exclude='*.aar' --exclude='*.jar' \
        --exclude='check-sensitive.sh' \
        . 2>/dev/null)
  if [ -n "$out" ]; then
    echo "❌ 命中敏感串 [$p]:"
    echo "$out" | sed 's/^/    /'
    HITS=$((HITS + 1))
  fi
done

if [ "$HITS" -eq 0 ]; then
  echo "✅ 合规自查通过：${#PATTERNS[@]} 个敏感串 0 命中"
  exit 0
else
  echo "总计 $HITS 条规则命中，请先处理再发布"
  exit 1
fi
