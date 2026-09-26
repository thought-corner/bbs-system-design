output "instance_ids" {
  description = "SSM 접속: aws ssm start-session --target <id>"
  value       = { for role, node in aws_instance.node : role => node.id }
}

output "private_ips" {
  description = "노드 사이 통신 주소"
  value       = { for role, node in aws_instance.node : role => node.private_ip }
}

output "grafana_url" {
  description = "허용한 CIDR에서만 열린다"
  value       = "http://${aws_instance.node["load"].public_ip}:3000"
}

output "ecr_repository_urls" {
  value = { for service, repository in aws_ecr_repository.service : service => repository.repository_url }
}
