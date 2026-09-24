output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "ALB と ECS のタスクを置く"
  value       = [for az in var.azs : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "Aurora を置く。インターネットへの経路はない"
  value       = [for az in var.azs : aws_subnet.private[az].id]
}

output "security_group_ids" {
  description = "役割ごとの SecurityGroup"
  value = {
    alb          = aws_security_group.alb.id
    quiz_service = aws_security_group.quiz_service.id
    db           = aws_security_group.db.id
  }
}
