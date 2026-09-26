#!/bin/bash
# 앱 노드: k3s 단일 노드. 서비스 배포와 ECR 인증 연동은 B단계에서 한다
set -euo pipefail
dnf install -y --allowerasing curl
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server --disable traefik --write-kubeconfig-mode 644" sh -
