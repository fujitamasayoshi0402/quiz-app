# syntax=docker/dockerfile:1
#
# web（Next.js）のコンテナイメージ。ビルドコンテキストはリポジトリのルート。
#   docker build -f apps/web/Dockerfile .
#
# バックエンドの場所は実行時に API_ORIGIN で渡す。ビルドには埋め込まない。

# Node.js のバージョンは .mise.toml と揃える
FROM node:22.23.2-bookworm-slim AS base
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
FROM node:22.23.2-bookworm-slim
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app

# standalone は静的ファイルを含まない（CDN から配る前提）。コンテナ単体で動かすため同梱する
COPY --from=build --chown=node:node /repo/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/web/.next/static ./apps/web/.next/static

# 公式イメージに用意されている root 以外の利用者
USER node
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
