# 인바운드만 좁힌다. 22번과 k3s API(6443)는 어디에도 열지 않는다 — 접속은 SSM으로 한다

resource "aws_security_group" "load" {
  name        = "${var.name_prefix}-load"
  description = "Load and monitoring node: Grafana only, from the allowed CIDR"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-load" }
}

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-app"
  description = "App node (k3s): service NodePorts from the load node only"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-app" }
}

resource "aws_security_group" "data" {
  name        = "${var.name_prefix}-data"
  description = "Data node: stores from the app node, exporters from the load node"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-data" }
}

resource "aws_vpc_security_group_ingress_rule" "load_grafana" {
  security_group_id = aws_security_group.load.id
  description       = "Grafana"
  cidr_ipv4         = var.grafana_allowed_cidr
  ip_protocol       = "tcp"
  from_port         = 3000
  to_port           = 3000
}

resource "aws_vpc_security_group_ingress_rule" "app_node_ports" {
  security_group_id            = aws_security_group.app.id
  description                  = "Service NodePorts for k6 load and Prometheus scrape"
  referenced_security_group_id = aws_security_group.load.id
  ip_protocol                  = "tcp"
  from_port                    = 30000
  to_port                      = 32767
}

locals {
  data_ports_from_app = {
    mysql = 3306
    redis = 6379
    kafka = 9092
  }
  data_ports_from_load = {
    mysqld-exporter = 9104
    redis-exporter  = 9121
    kafka-exporter  = 9308
    node-exporter   = 9100
  }
}

resource "aws_vpc_security_group_ingress_rule" "data_from_app" {
  for_each = local.data_ports_from_app

  security_group_id            = aws_security_group.data.id
  description                  = each.key
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

resource "aws_vpc_security_group_ingress_rule" "data_from_load" {
  for_each = local.data_ports_from_load

  security_group_id            = aws_security_group.data.id
  description                  = each.key
  referenced_security_group_id = aws_security_group.load.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

# 아웃바운드는 SSM·ECR·패키지 설치를 위해 전체 허용
resource "aws_vpc_security_group_egress_rule" "all" {
  for_each = {
    load = aws_security_group.load.id
    app  = aws_security_group.app.id
    data = aws_security_group.data.id
  }

  security_group_id = each.value
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
