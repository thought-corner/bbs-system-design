#!/usr/bin/env bash
# 배포된 데이터 노드에서 실측용 데이터를 적재한다 (deploy/data/seed/seed.sh를 SSM으로 실행).
# deploy.sh <ref>로 먼저 배포한다 — 적재 스크립트와 스키마는 그 묶음에 들어 있다.
#   deploy/aws/seed.sh            # 기본 규모: 게시판 10 × 100만, 인기 글 10만 × 댓글·좋아요 100
#   SEED_ARTICLES_PER_BOARD=100000 deploy/aws/seed.sh   # 규모를 바꿀 때 (SEED_* 환경 변수를 그대로 넘긴다)
set -euo pipefail
source "$(dirname "$0")/lib.sh"
# 1,000만 건 × 3 적재를 감당하는 제한 시간
SEED_TIMEOUT_SECONDS="${SEED_TIMEOUT_SECONDS:-10800}"

load_ecr
DATA_INSTANCE="$(tf_output instance_ids | json_field data)"
MYSQL_PASSWORD_PARAMETER="$(tf_output secret_parameter_names | json_field mysql_password)"

# 넘길 규모 값이 없으면 아무 일도 하지 않는 줄(:)을 보낸다
seed_env=":"
for name in SEED_BOARDS SEED_ARTICLES_PER_BOARD SEED_HOT_ARTICLES SEED_COMMENTS_PER_HOT SEED_LIKES_PER_HOT; do
  value="${!name:-}"
  if [ -n "$value" ]; then
    [[ "$value" =~ ^[0-9]+$ ]] || fail "${name}는 숫자여야 한다: ${value}"
    seed_env="${seed_env}; export ${name}=${value}"
  fi
done

started=$SECONDS
ssm_run "seed" "$DATA_INSTANCE" "$SEED_TIMEOUT_SECONDS" \
  "set -eu" \
  "test -f /opt/board/seed/seed.sh || { echo 'deploy.sh로 먼저 배포한다' >&2; exit 1; }" \
  "$(secret_env MYSQL_ROOT_PASSWORD "$MYSQL_PASSWORD_PARAMETER")" \
  "$seed_env" \
  "sh /opt/board/seed/seed.sh"
echo "적재 완료: $((SECONDS - started))초"
