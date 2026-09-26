#!/bin/sh
# 부하·모니터링 노드에서 실행한다 (SSM 또는 게이트의 docker exec). 환경 변수 GRAFANA_ADMIN_PASSWORD를 쓴다.
set -eu
cd "$(dirname "$0")"
: "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD가 없다}"
export GRAFANA_ADMIN_PASSWORD
docker compose -f compose.yml up -d --wait --wait-timeout 180 --remove-orphans
# 설정 파일은 바인드 마운트라 컨테이너가 다시 만들어지지 않는다. 재배포 때 바뀐 수집 대상을 읽게 한다
docker compose -f compose.yml kill -s HUP prometheus
docker compose -f compose.yml ps --format '{{.Service}} {{.Status}}'
