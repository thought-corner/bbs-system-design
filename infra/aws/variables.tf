variable "region" {
  description = "실험 리전. 세 노드와 ECR이 모두 여기 있다"
  type        = string
  default     = "ap-northeast-2"
}

variable "availability_zone" {
  description = "세 노드를 모두 둘 AZ. 한 AZ에 모아 네트워크 지연·AZ 간 전송이 측정을 흐리지 않게 한다"
  type        = string
  default     = "ap-northeast-2a"
}

variable "name_prefix" {
  description = "리소스 이름과 Project 태그 접두사"
  type        = string
  default     = "board-perf"
}

variable "grafana_allowed_cidr" {
  description = "Grafana(3000)에 접근을 허용할 CIDR. 보통 내 공인 IP/32. 기본값을 두지 않는다"
  type        = string

  validation {
    condition     = can(cidrnetmask(var.grafana_allowed_cidr)) && var.grafana_allowed_cidr != "0.0.0.0/0"
    error_message = "유효한 CIDR이어야 하고 0.0.0.0/0은 허용하지 않는다."
  }
}

variable "app_instance_type" {
  description = "앱 노드(k3s, 서비스 6개). Graviton"
  type        = string
  default     = "m7g.xlarge"
}

variable "data_instance_type" {
  description = "데이터 노드(MySQL·Redis·Kafka). Graviton"
  type        = string
  default     = "m7g.large"
}

variable "load_instance_type" {
  description = "부하·모니터링 노드(k6·Prometheus·Grafana). Graviton"
  type        = string
  default     = "c7g.large"
}

variable "root_volume_gb" {
  description = "앱·부하 노드의 gp3 루트 볼륨 크기"
  type        = number
  default     = 30
}

variable "data_root_volume_gb" {
  description = "데이터 노드의 gp3 루트 볼륨 크기. 게시글·댓글·좋아요 각 1,000만 건과 인덱스·Kafka 로그를 담는다"
  type        = number
  default     = 100
}

variable "service_names" {
  description = "ECR 저장소를 만들 서비스"
  type        = list(string)
  default     = ["article", "comment", "like", "view", "hot-article", "article-read"]
}
