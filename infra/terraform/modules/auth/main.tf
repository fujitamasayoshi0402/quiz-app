# 利用者の認証（ADR-0016）。Cognito の Managed Login で、パスワードとパスキーでサインインする。
#
#   ブラウザ → web（Next.js のサーバー）→ Managed Login（Cognito のドメイン）→ web のコールバック → トークンを HttpOnly の Cookie に置く
#
# Cognito が持つのは認証だけ。利用者の ID、所属、ロールはアプリの DB（core.users、core.tenant_members）にある。
# Cognito の Group は使わない。

data "aws_region" "current" {}

locals {
  # Managed Login のドメイン。独自ドメインにすると us-east-1 の証明書が要るため、Cognito のプレフィックスドメインを使う
  domain_prefix = "${var.name}-${random_string.domain_suffix.result}"
  domain_fqdn   = "${local.domain_prefix}.auth.${data.aws_region.current.region}.amazoncognito.com"
}

# プレフィックスはリージョンの中で、ほかのアカウントとも重ならない名前にする必要がある
resource "random_string" "domain_suffix" {
  length  = 6
  special = false
  upper   = false
}

resource "aws_cognito_user_pool" "this" {
  name = var.name

  # パスキー、パスワード以外のサインインの方式、Managed Login は Essentials 以上で使える。月間アクティブユーザー 10,000 人までは無料
  user_pool_tier = "ESSENTIALS"

  # メールアドレスでサインインする。利用者名は持たない
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  # 招待を受け入れるとき、確認済みのメールアドレスを招待と比べる（ADR-0016）。
  # 変えたアドレスを確かめるまでは、元のアドレスを使い続けさせる
  user_attribute_update_settings {
    attributes_require_verification_before_update = ["email"]
  }

  sign_in_policy {
    # メールのワンタイムパスワードは SES が要るため使わない。パスキーは、一度パスワードでサインインしてから登録する
    allowed_first_auth_factors = ["PASSWORD", "WEB_AUTHN"]
  }

  # RP ID は指定しない。独自ドメインがなければ、プレフィックスドメイン（local.domain_fqdn）になる。
  # 指定すると、User Pool を作る時点ではまだドメインがないため、Cognito が受け付けない。
  # 一度決まった RP ID を変えると、利用者はパスキーを登録し直す
  web_authn_configuration {
    # 必須にすると、生体認証や PIN を持たない認証器で登録できない
    user_verification = "preferred"
  }

  # 長さで強くする。文字の種類を強制すると、決まった形（先頭が大文字、末尾が記号など）に寄りやすい（NIST SP 800-63B）
  password_policy {
    minimum_length                   = 12
    require_lowercase                = false
    require_uppercase                = false
    require_numbers                  = false
    require_symbols                  = false
    password_history_size            = 5
    temporary_password_validity_days = 7
  }

  # パスキーをなくしたときは、メールで届くコードでパスワードを再設定してから、新しいパスキーを登録する
  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # 誰でもサインアップできる。所属がなければ、どのテナントのデータにも触れられない（ADR-0016）
  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  # Cognito の既定のメール（1 日 50 通まで）。確認コードとパスワードの再設定にだけ使う
  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }

  mfa_configuration   = "OFF"
  deletion_protection = var.deletion_protection ? "ACTIVE" : "INACTIVE"
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = local.domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id

  # 2 が Managed Login。1 は旧来の Hosted UI で、パスキーに対応しない
  managed_login_version = 2
}

# ---- web のクライアント ----

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  # トークンは Next.js のサーバーが受け取る。ブラウザには渡らないので、シークレットを持てる
  generate_secret = true

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  supported_identity_providers         = ["COGNITO"]

  # aws.cognito.signin.user.admin は、バックエンドが確認済みのメールアドレスを取る（GetUser）ために要る。
  # OIDC の userinfo は、API で取ったトークン（スモークテスト）が openid のスコープを持てないため使えない。
  # このスコープのトークンは自分の属性を書き換えられるが、トークンはブラウザに渡らない（web のサーバーが持つ）
  allowed_oauth_scopes = ["openid", "email", "aws.cognito.signin.user.admin"]

  callback_urls = [for origin in var.app_origins : "${origin}/auth/callback"]
  logout_urls   = [for origin in var.app_origins : "${origin}/"]

  # パスキーとパスワードの選択（USER_AUTH）と、トークンの更新だけを許す。
  # スモークテストも USER_AUTH で、パスワードを指定してトークンを取る（.github/scripts/smoke-token.sh）
  explicit_auth_flows = ["ALLOW_USER_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]

  # 登録されていないメールアドレスでも、同じ応答を返す。誰が登録しているかを探らせない
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 30
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

# Managed Login の画面の見た目。Cognito の既定のものを使う
resource "aws_cognito_managed_login_branding" "web" {
  user_pool_id = aws_cognito_user_pool.this.id
  client_id    = aws_cognito_user_pool_client.web.id

  use_cognito_provided_values = true
}

# ---- スモークテストの利用者 ----
# デプロイの最後のスモークテスト（tests/api）が、この利用者でトークンを取る。
# アプリの側は、同じメールアドレスで事前に登録した利用者（シードの R__smoke_data.sql）に、最初のログインで結び付く。
# example.com は誰も受け取れないアドレスなので、確認コードでほかの人が同じアドレスを確かめることはできない

resource "random_password" "smoke" {
  count = var.smoke_user_email == null ? 0 : 1

  length  = 32
  special = false
}

resource "aws_cognito_user" "smoke" {
  count = var.smoke_user_email == null ? 0 : 1

  user_pool_id = aws_cognito_user_pool.this.id
  username     = var.smoke_user_email
  password     = random_password.smoke[0].result

  attributes = {
    email          = var.smoke_user_email
    email_verified = "true"
  }

  # 作るときにメールを送らない
  message_action = "SUPPRESS"
}
