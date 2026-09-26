#!/bin/sh
# 데이터 노드에서 실행한다 (SSM 또는 게이트의 docker exec). 묶음 디렉터리의 node.env와 환경 변수 MYSQL_ROOT_PASSWORD를 쓴다.
# 다시 실행하면 바뀐 컨테이너만 새로 만든다 (MySQL 데이터는 볼륨에 남는다).
set -eu
cd "$(dirname "$0")"
. ./node.env
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD가 없다}"
export MYSQL_ROOT_PASSWORD DATA_ADVERTISED_HOST
docker compose -f compose.yml up -d --wait --wait-timeout 300 --remove-orphans
docker compose -f compose.yml ps --format '{{.Service}} {{.Status}}'
