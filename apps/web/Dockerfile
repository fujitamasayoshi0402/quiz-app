# syntax=docker/dockerfile:1
#
# web（Next.js）のコンテナイメージ。ビルドコンテキストはリポジトリのルート。
#   docker build -f apps/web/Dockerfile .
#
# バックエンドの場所は実行時に API_ORIGIN で渡す。ビルドには埋め込まない。

# Node.js のバージョンは .mise.toml と揃える
FROM node:22.23.3-bookworm-slim AS base
# pnpm はルートの package.json の packageManager に従う
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
    NEXT_TELEMETRY_DISABLED=1
RUN corepack enable
WORKDIR /repo

# --- 依存 --------------------------------------------------------------------
FROM base AS deps
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter web...

# --- ビルド ------------------------------------------------------------------
FROM deps AS build
COPY apps/web apps/web
RUN pnpm --filter web build

# --- 実行 --------------------------------------------------------------------
FROM node:22.23.3-bookworm-slim
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app

# standalone は静的ファイルを含まない（CDN から配る前提）。コンテナ単体で動かすため同梱する
COPY --from=build --chown=node:node /repo/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/web/.next/static ./apps/web/.next/static

# Debian のセキュリティの更新を入れる（DEV-132）。公式イメージは、Debian が修正を出してから作り直されるまでに数日かかり、
# 版を固定したタグ（22.23.x）は、次の版が出ると作り直されなくなる。その間も、CI の脆弱性の検査を通す
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# 実行には node だけを使う。公式イメージに入っている npm（と、npm が抱える依存）は脆弱性の検査に掛かるだけなので外す（DEV-113）
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx

# 公式イメージに用意されている root 以外の利用者
USER node
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
