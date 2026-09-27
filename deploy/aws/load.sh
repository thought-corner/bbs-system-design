#!/usr/bin/env bash
# 부하 노드에서 k6 실험 하나를 돌리고(SSM) 요약을 보여 준다. deploy.sh·seed.sh 뒤에 쓴다.
#   deploy/aws/load.sh <write-mix|read-detail|hot-contention>
#   STAGES=200:3m,400:3m deploy/aws/load.sh write-mix     # 계단을 바꿀 때 (아래 환경 변수를 그대로 넘긴다)
# 결과는 Grafana의 k6 대시보드에서 testid로 고른다.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
SCENARIO="${1:?시나리오 (write-mix|read-detail|hot-contention)}"
case "$SCENARIO" in write-mix|read-detail|hot-contention) ;; *) fail "모르는 시나리오: ${SCENARIO}" ;; esac
LOAD_TIMEOUT_SECONDS="${LOAD_TIMEOUT_SECONDS:-5400}"
TESTID="${TESTID:-${SCENARIO}-$(date -u +%Y%m%dT%H%M%SZ)}"
[[ "$TESTID" =~ ^[A-Za-z0-9._-]+$ ]] || fail "TESTID는 영문·숫자·._-만: ${TESTID}"

load_ecr
LOAD_INSTANCE="$(tf_output instance_ids | json_field load)"

load_env="export TESTID=${TESTID}"
for name in STAGES HOT_SAMPLE SAMPLE_OFFSET CONTENTION_ITERATIONS CONTENTION_VUS PRE_VUS MAX_VUS SEED_BOARDS CONSISTENCY_WAIT_SECONDS LOGICAL_TTL_SECONDS; do
  value="${!name:-}"
  if [ -n "$value" ]; then
    [[ "$value" =~ ^[0-9a-z:,]+$ ]] || fail "${name} 값이 이상하다: ${value}"
    load_env="${load_env}; export ${name}=${value}"
  fi
done

started=$SECONDS
ssm_run "k6 ${SCENARIO}" "$LOAD_INSTANCE" "$LOAD_TIMEOUT_SECONDS" \
  "set -eu" \
  "test -f /opt/board/k6/run.sh || { echo 'deploy.sh로 먼저 배포한다' >&2; exit 1; }" \
  "$load_env" \
  "rc=0; sh /opt/board/k6/run.sh ${SCENARIO} > /opt/board/k6/last-run.log 2>&1 || rc=\$?" \
  "tail -60 /opt/board/k6/last-run.log; exit \$rc"
echo "k6 ${SCENARIO} 끝: $((SECONDS - started))초, testid=${TESTID}"
echo "Grafana: $(terraform -chdir="$TF_DIR" output -raw grafana_url) → 7. 부하(k6) 대시보드에서 testid=${TESTID}"
