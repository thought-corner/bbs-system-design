data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  nodes = {
    app = {
      instance_type  = var.app_instance_type
      security_group = aws_security_group.app.id
      user_data      = "app.sh"
      volume_gb      = var.root_volume_gb
    }
    data = {
      instance_type  = var.data_instance_type
      security_group = aws_security_group.data.id
      user_data      = "docker.sh"
      volume_gb      = var.data_root_volume_gb
    }
    load = {
      instance_type  = var.load_instance_type
      security_group = aws_security_group.load.id
      user_data      = "docker.sh"
      volume_gb      = var.root_volume_gb
    }
  }
}

resource "aws_instance" "node" {
  for_each = local.nodes

  ami                    = nonsensitive(data.aws_ssm_parameter.al2023_arm64.value)
  instance_type          = each.value.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [each.value.security_group]
  iam_instance_profile   = aws_iam_instance_profile.node.name
  user_data              = file("${path.module}/user_data/${each.value.user_data}")

  # destroy로 반드시 지워지게 한다
  disable_api_termination = false

  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = each.value.volume_gb
    delete_on_termination = true
    encrypted             = true
  }

  tags = { Name = "${var.name_prefix}-${each.key}" }
}
