resource "aws_ecr_repository" "service" {
  for_each = toset(var.service_names)

  name                 = "${var.name_prefix}/${each.value}"
  image_tag_mutability = "MUTABLE"
  # destroy 때 이미지가 남아 있어도 저장소를 지운다
  force_delete = true
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each = aws_ecr_repository.service

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the latest 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
