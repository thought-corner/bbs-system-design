#!/usr/bin/env bash
# 노드별 배포 묶음(app·data·load)을 만든다. AWS·Docker를 부르지 않는 순수 로컬 작업이다.
#   APP_HOST=<앱 사설 IP> DATA_HOST=<데이터 사설 IP> REPO_PREFIX=<저장소 접두사> IMAGE_TAG=<태그> \
#     [MYSQL_BUFFER_POOL_SIZE=<기본 5G>] deploy/aws/render.sh <소스 디렉터리> <출력 디렉터리>
# 비밀 값은 묶음에 넣지 않는다. 각 노드의 run.sh가 실행 때 환경 변수로 받는다.
set -euo pipefail
SOURCE_DIR="${1:?소스 디렉터리}"
OUT_DIR="${2:?출력 디렉터리}"
: "${APP_HOST:?APP_HOST}" "${DATA_HOST:?DATA_HOST}" "${REPO_PREFIX:?REPO_PREFIX}" "${IMAGE_TAG:?IMAGE_TAG}"
SERVICES=(article comment like view hot-article article-read)

mkdir -p "${OUT_DIR}/app/k8s" "${OUT_DIR}/data" "${OUT_DIR}/load/grafana"

# 데이터 노드: MySQL·Redis·Kafka·exporter. 초기화 SQL은 묶음 안으로 옮긴다
sed 's#\.\./\.\./docker/mysql/init\.sql#./init.sql#' "${SOURCE_DIR}/deploy/data/compose.yml" > "${OUT_DIR}/data/compose.yml"
grep -q '\./init\.sql:' "${OUT_DIR}/data/compose.yml" || { echo "data compose의 init.sql 경로를 바꾸지 못했다" >&2; exit 1; }
cp "${SOURCE_DIR}/docker/mysql/init.sql" "${OUT_DIR}/data/init.sql"
cp "${SOURCE_DIR}/deploy/data/run.sh" "${OUT_DIR}/data/run.sh"
printf 'DATA_ADVERTISED_HOST=%s\nMYSQL_BUFFER_POOL_SIZE=%s\n' "$DATA_HOST" "${MYSQL_BUFFER_POOL_SIZE:-5G}" > "${OUT_DIR}/data/node.env"
# 적재 스크립트와 서비스 스키마 (적재는 서비스 기동 전에도 테이블을 만들 수 있다)
cp -R "${SOURCE_DIR}/deploy/data/seed" "${OUT_DIR}/data/seed"
mkdir -p "${OUT_DIR}/data/seed/schema"
cp "${SOURCE_DIR}/service/article/src/main/resources/schema.sql" "${OUT_DIR}/data/seed/schema/article.sql"
cp "${SOURCE_DIR}/service/comment/src/main/resources/schema.sql" "${OUT_DIR}/data/seed/schema/comment.sql"
cp "${SOURCE_DIR}/service/like/src/main/resources/schema.sql" "${OUT_DIR}/data/seed/schema/article_like.sql"
cp "${SOURCE_DIR}/service/view/src/main/resources/schema.sql" "${OUT_DIR}/data/seed/schema/article_view.sql"

# 앱 노드: 기준 매니페스트 + 이미지 저장소·태그를 바꾸는 오버레이
cp -R "${SOURCE_DIR}/deploy/k8s" "${OUT_DIR}/app/k8s/base"
{
  echo "apiVersion: kustomize.config.k8s.io/v1beta1"
  echo "kind: Kustomization"
  echo "resources:"
  echo "  - base"
  echo "images:"
  for service in "${SERVICES[@]}"; do
    printf '  - name: board-perf/%s\n    newName: %s/%s\n    newTag: "%s"\n' "$service" "$REPO_PREFIX" "$service" "$IMAGE_TAG"
  done
} > "${OUT_DIR}/app/k8s/kustomization.yaml"
cp "${SOURCE_DIR}/deploy/app/run.sh" "${OUT_DIR}/app/run.sh"
printf 'DATA_HOST=%s\n' "$DATA_HOST" > "${OUT_DIR}/app/node.env"

# 부하 노드: Prometheus(노드 주소를 채운다)·Grafana(로컬과 같은 프로비저닝·대시보드)
cp "${SOURCE_DIR}/deploy/load/compose.yml" "${OUT_DIR}/load/compose.yml"
sed -e "s/__APP_HOST__/${APP_HOST}/g" -e "s/__DATA_HOST__/${DATA_HOST}/g" \
  "${SOURCE_DIR}/deploy/load/prometheus.yml" > "${OUT_DIR}/load/prometheus.yml"
if grep -q '__[A-Z_]*_HOST__' "${OUT_DIR}/load/prometheus.yml"; then echo "prometheus.yml에 채우지 못한 자리가 있다" >&2; exit 1; fi
cp -R "${SOURCE_DIR}/monitoring/grafana/provisioning" "${OUT_DIR}/load/grafana/provisioning"
cp -R "${SOURCE_DIR}/monitoring/grafana/dashboards" "${OUT_DIR}/load/grafana/dashboards"
cp "${SOURCE_DIR}/deploy/load/run.sh" "${OUT_DIR}/load/run.sh"
# k6 실험과 앱 노드 주소 (k6/run.sh가 읽는다)
cp -R "${SOURCE_DIR}/deploy/load/k6" "${OUT_DIR}/load/k6"
printf 'APP_HOST=%s\n' "$APP_HOST" > "${OUT_DIR}/load/node.env"

echo "묶음: ${OUT_DIR}/{app,data,load}"
