#!/bin/sh
# Snowflake node-id = 서비스별 기준값 + StatefulSet 파드 순번. 복제를 늘려도 ID가 겹치지 않는다 (D2).
set -eu
if [ -n "${SNOWFLAKE_NODE_ID_BASE:-}" ]; then
  SNOWFLAKE_NODE_ID=$((SNOWFLAKE_NODE_ID_BASE + ${POD_INDEX:?POD_INDEX가 없다}))
  export SNOWFLAKE_NODE_ID
fi
# JAVA_OPTS는 단어 단위로 나눠 넘긴다
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} org.springframework.boot.loader.launch.JarLauncher "$@"
