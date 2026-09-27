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

# 노드에서 SecureString 파라미터를 읽어 환경 변수로 내보내는 셸 줄. 값이 아니라 이름만 담는다
secret_env() {
  printf '%s="$(aws ssm get-parameter --region %s --name %s --with-decryption --query Parameter.Value --output text)"; export %s' \
    "$1" "$AWS_REGION" "$2" "$1"
}

# 셸 명령 줄들을 SSM RunShellScript로 한 노드에 보내고 끝날 때까지 기다린다. 실패하면 출력 끝을 보여 주고 멈춘다
#   ssm_run <이름> <인스턴스 ID> <제한 시간(초)> <명령 줄>...
ssm_run() {
  local label="$1" instance_id="$2" timeout="$3" request command_id status deadline
  shift 3
  request="$(mktemp)"
  INSTANCE_ID="$instance_id" TIMEOUT="$timeout" python3 -c '
import json, os, sys
timeout = os.environ["TIMEOUT"]
print(json.dumps({
    "InstanceIds": [os.environ["INSTANCE_ID"]],
    "DocumentName": "AWS-RunShellScript",
    "TimeoutSeconds": int(timeout),
    "Parameters": {"commands": sys.argv[1:], "executionTimeout": [timeout]},
}))' "$@" > "$request"
  echo "--- ${label} (${instance_id})"
  command_id="$(aws ssm send-command --region "$AWS_REGION" --cli-input-json "file://${request}" \
    --query Command.CommandId --output text)"
  rm -f "$request"
  deadline=$((SECONDS + timeout + 60))
  while :; do
    status="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
      --instance-id "$instance_id" --query Status --output text 2>/dev/null || echo Pending)"
    case "$status" in
      Success) break ;;
      Failed|Cancelled|TimedOut|Cancelling)
        aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" --instance-id "$instance_id" \
          --query '[StandardOutputContent,StandardErrorContent]' --output text | tail -40 >&2
        fail "${label} ${status}" ;;
    esac
    [ "$SECONDS" -lt "$deadline" ] || fail "${label} 명령이 끝나지 않는다 (${command_id})"
    sleep 5
  done
  aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" --instance-id "$instance_id" \
    --query StandardOutputContent --output text | tail -20
}
