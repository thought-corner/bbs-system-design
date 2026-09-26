#!/bin/sh
# 앱 노드(k3s)에서 실행한다 (SSM 또는 게이트의 docker exec). 묶음 디렉터리의 node.env와 환경 변수 MYSQL_PASSWORD를 쓴다.
# ECR_REGISTRY·AWS_REGION이 있으면 ECR 토큰(12시간 유효)을 받아 k3s 레지스트리 인증을 갱신한다.
set -eu
cd "$(dirname "$0")"
PATH="${PATH}:/usr/local/bin"
. ./node.env
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD가 없다}"

wait_node_ready() {
  i=0
  until kubectl get nodes 2>/dev/null | grep -q ' Ready '; do
    i=$((i + 1))
    [ "$i" -le 60 ] || { echo "k3s 노드가 Ready가 되지 않는다" >&2; exit 1; }
    sleep 2
  done
}

if [ -n "${ECR_REGISTRY:-}" ]; then
  token="$(aws ecr get-login-password --region "${AWS_REGION:?AWS_REGION이 없다}")"
  umask 077
  mkdir -p /etc/rancher/k3s
  cat > /etc/rancher/k3s/registries.yaml.new <<YAML
configs:
  "${ECR_REGISTRY}":
    auth:
      username: AWS
      password: "${token}"
YAML
  mv /etc/rancher/k3s/registries.yaml.new /etc/rancher/k3s/registries.yaml
  # 레지스트리 설정은 k3s 시작 때 읽는다. 재시작해도 떠 있는 파드는 그대로다
  systemctl restart k3s
  umask 022
fi
wait_node_ready

kubectl create namespace board --dry-run=client -o yaml | kubectl apply -f -
kubectl -n board create configmap board-data \
  --from-literal=MYSQL_HOST="${DATA_HOST}" \
  --from-literal=REDIS_HOST="${DATA_HOST}" \
  --from-literal=KAFKA_BOOTSTRAP_SERVERS="${DATA_HOST}:9092" \
  --dry-run=client -o yaml | kubectl apply -f -
# 비밀번호가 프로세스 인자에 보이지 않게 파일로 넘기고 바로 지운다
secret_file="$(mktemp)"
trap 'rm -f "$secret_file"' EXIT
printf '%s' "$MYSQL_PASSWORD" > "$secret_file"
kubectl -n board create secret generic board-mysql --from-file=password="$secret_file" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -k k8s
for statefulset in $(kubectl -n board get statefulset -o name); do
  kubectl -n board rollout status "$statefulset" --timeout=300s
done
kubectl -n board get pods -o wide
