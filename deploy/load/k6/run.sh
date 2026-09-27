#!/bin/sh
# 부하 노드에서 k6 실험 하나를 돌린다 (deploy/aws/load.sh 또는 게이트의 docker exec).
#   sh k6/run.sh <write-mix|read-detail|hot-contention>
# k6는 부하 compose 네트워크에서 돌아 Prometheus로 지표를 보낸다(remote write). 요약은 k6/out/<TESTID>*.json.
# 규모는 환경 변수로 넘긴다: STAGES("초당건수:기간,..."), HOT_SAMPLE, CONTENTION_ITERATIONS, CONTENTION_VUS, PRE_VUS, MAX_VUS
set -eu
cd "$(dirname "$0")"
. ../node.env
scenario="${1:?시나리오 이름 (write-mix|read-detail|hot-contention)}"
[ -f "${scenario}.js" ] || { echo "시나리오 ${scenario}.js가 없다" >&2; exit 1; }
TESTID="${TESTID:-${scenario}-$(date -u +%Y%m%dT%H%M%SZ)}"
export APP_HOST TESTID
mkdir -p out
# k6 이미지는 root가 아닌 사용자로 돈다. 요약을 쓸 수 있게 연다
chmod 777 out

echo "k6 ${scenario} (testid=${TESTID}, app=${APP_HOST})"
docker run --rm --network board-load_default -v "$(pwd):/scripts" \
  -e APP_HOST -e TESTID -e STAGES -e HOT_SAMPLE -e CONTENTION_ITERATIONS -e CONTENTION_VUS -e PRE_VUS -e MAX_VUS \
  -e SEED_BOARDS -e CONSISTENCY_WAIT_SECONDS -e LOGICAL_TTL_SECONDS -e PROMETHEUS_URL \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max' \
  grafana/k6:0.54.0 run --quiet --out experimental-prometheus-rw --tag "testid=${TESTID}" \
  --summary-export "/scripts/out/${TESTID}.json" "/scripts/${scenario}.js"

# 몰기 실험은 요약에 정확성 결과를 남긴다. 요약이 없거나 어긋나면 실패로 끝낸다
if [ "$scenario" = hot-contention ]; then
  [ -f "out/${TESTID}-summary.json" ] || { echo "몰기 실험 요약(out/${TESTID}-summary.json)이 없다" >&2; exit 1; }
  grep -q '"contention_ok":true' "out/${TESTID}-summary.json" \
    || { echo "몰기 실험: 좋아요·조회 수가 성공 응답 수와 맞지 않는다" >&2; exit 1; }
fi
