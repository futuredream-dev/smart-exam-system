#!/bin/sh
set -eu
# 只检查，不打印秘密。拒绝示例值，避免带着演示密钥部署。
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"
case "$DB_PASSWORD" in change_me*) echo 'Replace the example database password.' >&2; exit 1;; esac
case "$JWT_SECRET" in change_me*) echo 'Replace the example JWT secret.' >&2; exit 1;; esac
if [ "${#JWT_SECRET}" -lt 32 ]; then
  echo 'JWT_SECRET must contain at least 32 ASCII characters.' >&2
  exit 1
fi
exec java -Duser.timezone=Asia/Shanghai -jar /app/app.jar
