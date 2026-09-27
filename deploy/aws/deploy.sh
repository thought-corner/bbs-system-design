#!/usr/bin/env bash
# ref(태그 권장)의 설정으로 노드 3대에 배포한다: 데이터 → 앱 → 부하 순서로 묶음을 SSM으로 보내 run.sh를 실행한다.
# 이미지는 push-images.sh로 같은 ref를 먼저 푸시해 둔다.
#   deploy/aws/deploy.sh <ref>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
REF="${1:?ref (예: perf-v1)}"
SERVICES=(article comment like view hot-article article-read)
# SSM send-command 요청 크기 한도(문서 매개변수 약 64KB)보다 넉넉히 작게 잡는다
MAX_PAYLOAD_BYTES=48000
SSM_TIMEOUT_SECONDS=900

COMMIT="$(resolve_commit "$REF")"
IMAGE_TAG="$(image_tag_for "$COMMIT")"
load_ecr
INSTANCE_IDS="$(tf_output instance_ids)"
PRIVATE_IPS="$(tf_output private_ips)"
APP_HOST="$(json_field app <<< "$PRIVATE_IPS")"
DATA_HOST="$(json_field data <<< "$PRIVATE_IPS")"
GRAFANA_URL="$(terraform -chdir="$TF_DIR" output -raw grafana_url)"

echo "--- 이미지 확인: ${IMAGE_TAG}"
for service in "${SERVICES[@]}"; do
  aws ecr describe-images --region "$AWS_REGION" --repository-name "${REPO_PREFIX#*/}/${service}" \
    --image-ids "imageTag=${IMAGE_TAG}" >/dev/null 2>&1 \
    || fail "ECR에 ${service}:${IMAGE_TAG}이 없다 (deploy/aws/push-images.sh ${REF} 먼저)"
done

# 비밀번호는 Terraform이 만든 SecureString 파라미터에 있다. 노드가 직접 읽으므로 SSM 명령에는 이름만 들어간다
SECRET_NAMES="$(tf_output secret_parameter_names)"
MYSQL_PASSWORD_PARAMETER="$(json_field mysql_password <<< "$SECRET_NAMES")"
GRAFANA_PASSWORD_PARAMETER="$(json_field grafana_admin_password <<< "$SECRET_NAMES")"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "${WORK_DIR}/src" "${WORK_DIR}/out"
git -C "$ROOT_DIR" archive "$COMMIT" deploy docker/mysql monitoring/grafana \
  service/article/src/main/resources/schema.sql service/comment/src/main/resources/schema.sql \
  service/like/src/main/resources/schema.sql service/view/src/main/resources/schema.sql | tar -x -C "${WORK_DIR}/src"
APP_HOST="$APP_HOST" DATA_HOST="$DATA_HOST" REPO_PREFIX="$REPO_PREFIX" IMAGE_TAG="$IMAGE_TAG" \
  bash "${WORK_DIR}/src/deploy/aws/render.sh" "${WORK_DIR}/src" "${WORK_DIR}/out" >/dev/null

# 묶음을 풀고 run.sh를 실행한다
run_on_node() {
  local role="$1" env_line="$2" payload
  # macOS tar가 확장 속성을 ._* 파일로 함께 묶지 않게 한다 (Grafana가 ._board.yml을 설정으로 읽다 죽는다)
  payload="$(COPYFILE_DISABLE=1 tar -czf - -C "${WORK_DIR}/out/${role}" . | base64 | tr -d '\n')"
  [ "${#payload}" -le "$MAX_PAYLOAD_BYTES" ] || fail "${role} 묶음이 SSM 한도를 넘는다 (${#payload}B > ${MAX_PAYLOAD_BYTES}B)"
  ssm_run "$role" "$(json_field "$role" <<< "$INSTANCE_IDS")" "$SSM_TIMEOUT_SECONDS" \
    "set -eu" \
    "rm -rf /opt/board.next && mkdir -p /opt/board.next" \
    "echo '${payload}' | base64 -d | tar -xz --no-same-owner --exclude='._*' -C /opt/board.next" \
    "rm -rf /opt/board && mv /opt/board.next /opt/board" \
    "$env_line" \
    "sh /opt/board/run.sh"
}

run_on_node data "$(secret_env MYSQL_ROOT_PASSWORD "$MYSQL_PASSWORD_PARAMETER")"
run_on_node app "$(secret_env MYSQL_PASSWORD "$MYSQL_PASSWORD_PARAMETER"); export ECR_REGISTRY='${ECR_REGISTRY}' AWS_REGION='${AWS_REGION}'"
run_on_node load "$(secret_env GRAFANA_ADMIN_PASSWORD "$GRAFANA_PASSWORD_PARAMETER")"

echo "배포 완료: ${REF} (${COMMIT:0:12}), 이미지 태그 ${IMAGE_TAG}"
echo "Grafana: ${GRAFANA_URL} (admin / 비밀번호: aws ssm get-parameter --region ${AWS_REGION} --name ${GRAFANA_PASSWORD_PARAMETER} --with-decryption --query Parameter.Value --output text)"
