#!/bin/bash
# 데이터·부하 노드: Docker와 compose 플러그인까지만. 노드별 구성은 C단계에서 한다
set -euo pipefail
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ssm-user 2>/dev/null || true
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sfL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-aarch64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
