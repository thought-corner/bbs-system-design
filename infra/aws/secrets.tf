# 배포가 쓰는 비밀번호. 값은 SecureString 파라미터와 로컬 상태 파일(gitignore 대상)에만 있다.
# 노드는 인스턴스 프로파일의 AmazonSSMManagedInstanceCore(ssm:GetParameter)로 읽으므로 SSM 명령에는 파라미터 이름만 남는다.
# destroy가 파라미터도 함께 지운다.

resource "random_password" "mysql" {
  length  = 32
  special = false
}

resource "random_password" "grafana_admin" {
  length  = 24
  special = false
}

resource "aws_ssm_parameter" "mysql_password" {
  name  = "/${var.name_prefix}/mysql-password"
  type  = "SecureString"
  value = random_password.mysql.result
}

resource "aws_ssm_parameter" "grafana_admin_password" {
  name  = "/${var.name_prefix}/grafana-admin-password"
  type  = "SecureString"
  value = random_password.grafana_admin.result
}
