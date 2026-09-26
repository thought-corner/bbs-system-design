#!/usr/bin/env bash
# ref(태그 권장)의 코드로 이미지 6개를 빌드해 ECR에 푸시한다. 작업 트리가 아니라 ref를 임시 worktree로 꺼내 빌드한다.
#   deploy/aws/push-images.sh <ref>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
REF="${1:?ref (예: perf-v1)}"
SERVICES=(article comment like view hot-article article-read)

COMMIT="$(resolve_commit "$REF")"
IMAGE_TAG="$(image_tag_for "$COMMIT")"
load_ecr

WORKTREE="$(mktemp -d)"
cleanup() { git -C "$ROOT_DIR" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || rm -rf "$WORKTREE"; }
trap cleanup EXIT
git -C "$ROOT_DIR" worktree add --detach "$WORKTREE" "$COMMIT" >/dev/null

echo "--- 빌드: ${REF} (${COMMIT:0:12}) → 태그 ${IMAGE_TAG}"
bash "${ROOT_DIR}/deploy/aws/build-images.sh" "$WORKTREE" "$REPO_PREFIX" "$IMAGE_TAG"

echo "--- 푸시: ${REPO_PREFIX}"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
for service in "${SERVICES[@]}"; do
  docker push -q "${REPO_PREFIX}/${service}:${IMAGE_TAG}"
done
echo "이미지 태그: ${IMAGE_TAG}"
