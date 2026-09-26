#!/usr/bin/env bash
# push-images.sh·deploy.sh가 함께 쓰는 함수. 직접 실행하지 않는다.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TF_DIR="${ROOT_DIR}/infra/aws"

fail() { echo "FAIL: $1" >&2; exit 1; }

# ref(태그·브랜치·커밋)를 커밋 해시로 푼다. 작업 트리의 커밋되지 않은 변경은 배포에 들어가지 않는다
resolve_commit() {
  git -C "$ROOT_DIR" rev-parse --verify --quiet "${1}^{commit}" || fail "ref를 찾지 못했다: ${1}"
}

# 이미지 태그: ref가 가리키는 커밋에 붙은 git 태그 이름, 없으면 짧은 커밋 해시
image_tag_for() {
  local commit="$1" tag
  tag="$(git -C "$ROOT_DIR" describe --exact-match --tags "$commit" 2>/dev/null || true)"
  if [ -n "$tag" ]; then echo "$tag"; else git -C "$ROOT_DIR" rev-parse --short=12 "$commit"; fi
}

tf_output() {
  terraform -chdir="$TF_DIR" output -json "$1" 2>/dev/null || fail "Terraform 출력 ${1}이 없다 (infra/aws apply 먼저)"
}
json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

# ECR 저장소 URL(<계정>.dkr.ecr.<리전>.amazonaws.com/<접두사>/<서비스>)에서 레지스트리·접두사·리전을 얻는다
load_ecr() {
  local article_url
  article_url="$(tf_output ecr_repository_urls | json_field article)"
  ECR_REGISTRY="${article_url%%/*}"
  REPO_PREFIX="${article_url%/*}"
  AWS_REGION="$(echo "$ECR_REGISTRY" | cut -d. -f4)"
  [ -n "$AWS_REGION" ] || fail "ECR 주소에서 리전을 읽지 못했다: ${ECR_REGISTRY}"
}
