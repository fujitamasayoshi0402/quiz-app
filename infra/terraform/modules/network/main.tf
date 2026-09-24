# VPC とサブネット。NAT Gateway も VPC Endpoint も置かない（ADR-0013）。
#
# | サブネット | 置くもの                     | インターネットへの経路 |
# | ---------- | ---------------------------- | ---------------------- |
# | public     | ALB、ECS のタスク            | Internet Gateway       |
# | private    | Aurora                       | なし                   |
#
# ECS のタスクはパブリック IP を持ち、ECR や CloudWatch Logs などの AWS の API へ直接出る。
# 外から届くかどうかは、security_groups.tf の SecurityGroup だけで決まる。

locals {
  # AZ ごとの番号。サブネットの CIDR（/24）を割り当てるのに使う
  #   public:  10.x.0.0/24、10.x.1.0/24、...
  #   private: 10.x.10.0/24、10.x.11.0/24、...
  azs = { for i, az in var.azs : az => i }
}

resource "aws_vpc" "this" {
  cidr_block = var.cidr_block

  # 既定の VPC と揃える
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = var.name
  }
}

# 既定の SecurityGroup からルールをすべて外す。
# 既定のままだと「同じ SG どうしは全ポート許可、外向きは全許可」で、SG を指定し忘れたリソースに付く
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-default-unused"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = var.name
  }
}

# ---- public ----

resource "aws_subnet" "public" {
  for_each = local.azs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.cidr_block, 8, each.value)

  # パブリック IP は ECS のサービス側で付ける（assign_public_ip）。サブネットでは既定で付けない
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name}-public-${each.key}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# ---- private ----

resource "aws_subnet" "private" {
  for_each = local.azs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.cidr_block, 8, each.value + 10)

  tags = {
    Name = "${var.name}-private-${each.key}"
    Tier = "private"
  }
}

# VPC の中への経路（local）だけを持つ。メインのルートテーブルに頼らず、明示して関連付ける
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-private"
  }
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}
