#!/usr/bin/env bash
# 서비스 이미지 6개(linux/arm64)를 빌드한다. 푸시하지 않는다.
#   deploy/aws/build-images.sh <소스 디렉터리> <저장소 접두사> <태그>
#   예) deploy/aws/build-images.sh . board-perf dev → board-perf/article:dev ...
set -euo pipefail
SOURCE_DIR="${1:?소스 디렉터리}"
REPO_PREFIX="${2:?저장소 접두사}"
IMAGE_TAG="${3:?태그}"
SERVICES=(article comment like view hot-article article-read)

(cd "$SOURCE_DIR" && ./gradlew bootJar -q)
for service in "${SERVICES[@]}"; do
  docker build -q --platform linux/arm64 -f "${SOURCE_DIR}/deploy/Dockerfile" --build-arg "SERVICE=${service}" \
    -t "${REPO_PREFIX}/${service}:${IMAGE_TAG}" "$SOURCE_DIR" >/dev/null
  echo "${REPO_PREFIX}/${service}:${IMAGE_TAG}"
done
