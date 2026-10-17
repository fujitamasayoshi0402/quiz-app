output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "ECS のタスクを置く"
  value       = [for az in var.azs : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "Aurora と API Gateway の VPC リンクを置く。インターネットへの経路はない"
  value       = [for az in var.azs : aws_subnet.private[az].id]
}

output "security_group_ids" {
  description = "役割ごとの SecurityGroup"
  value = {
    vpc_link     = aws_security_group.vpc_link.id
    quiz_service = aws_security_group.quiz_service.id
    db           = aws_security_group.db.id
  }
}

output "ipv6_cidr_block" {
  description = "VPC の IPv6 の CIDR（/56）。ipv6 が false なら null"
  value       = var.ipv6 ? aws_vpc.this.ipv6_cidr_block : null
}

output "egress_only_internet_gateway_id" {
  description = "IPv6 の出口。ipv6 が false なら null"
  value       = var.ipv6 ? aws_egress_only_internet_gateway.this[0].id : null
}
