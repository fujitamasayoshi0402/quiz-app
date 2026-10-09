"""GitHub Actions のキャッシュのうち、もう使われないものを消す（DEV-101）。

リポジトリのキャッシュには上限（10 GB）があり、超えると GitHub が最後に使われたのが古いものから消す。
使われないものが上限を埋めると、たまにしか使わないが効いているキャッシュ（デプロイの arm64 のレイヤーなど）が先に消されうる。

消すもの。

- 閉じた PR のキャッシュ。その PR からしか読めず、閉じたあとは使われない
- develop / main のキャッシュのうち、最後に使われてから KEEP_DAYS 日を超えたもの。
  中身が変わって新しいキーで書き直されたあとの古いもの（Gradle の依存、pnpm のストア、ツールの版を上げる前の mise）。
  キーの形からは古いかどうかを決められない。mise は、同じ形のキーでジョブごとに別のツールの組を持つ

イメージのレイヤー（buildx の type=gha。キーが buildkit- / index- で始まる）は消さない。
索引（index-）とレイヤー（buildkit-blob-）は組で使われ、どのレイヤーをまだ索引が指しているかはキーから分からない。
GitHub の追い出しに任せる。

    GH_TOKEN=... GITHUB_REPOSITORY=<所有者>/<名前> python3 .github/scripts/cleanup-caches.py [--dry-run]

結果は Markdown で標準出力に出す（ジョブのサマリー）。
"""

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

KEEP_DAYS = 3
BRANCHES = ("refs/heads/develop", "refs/heads/main")
IMAGE_LAYER_PREFIXES = ("buildkit-", "index-")
PULL_REF = re.compile(r"^refs/pull/(\d+)/merge$")


def gh_api(*args):
    return subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True).stdout


def list_caches(repo):
    lines = gh_api("--paginate", f"repos/{repo}/actions/caches?per_page=100", "--jq", ".actions_caches[]")
    return [json.loads(line) for line in lines.splitlines() if line]


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def reason_to_delete(cache, now, closed_pull):
    """消す理由を返す。残すなら None"""
    ref = cache["ref"]
    pull = PULL_REF.match(ref)
    if pull:
        return "閉じた PR" if closed_pull(int(pull.group(1))) else None
    if ref not in BRANCHES or cache["key"].startswith(IMAGE_LAYER_PREFIXES):
        return None
    if now - parse_time(cache["last_accessed_at"]) > timedelta(days=KEEP_DAYS):
        return f"{KEEP_DAYS} 日を超えて使われていない"
    return None


def gib(size):
    return f"{size / 2**30:.2f} GB"


def main(dry_run):
    repo = os.environ["GITHUB_REPOSITORY"]
    now = datetime.now(timezone.utc)

    states = {}

    def closed_pull(number):
        if number not in states:
            states[number] = gh_api(f"repos/{repo}/pulls/{number}", "--jq", ".state").strip()
        return states[number] == "closed"

    caches = list_caches(repo)
    targets = [(cache, reason) for cache in caches if (reason := reason_to_delete(cache, now, closed_pull))]

    for cache, _ in targets:
        if not dry_run:
            gh_api("--method", "DELETE", f"repos/{repo}/actions/caches/{cache['id']}")

    total = sum(c["size_in_bytes"] for c in caches)
    removed = sum(c["size_in_bytes"] for c, _ in targets)
    print("### キャッシュの片付け" + ("（試しに流しただけで、消していない）" if dry_run else ""))
    print()
    print(f"- 片付ける前: {len(caches)} 件 {gib(total)}")
    print(f"- 消したもの: {len(targets)} 件 {gib(removed)}")
    print(f"- 残り: {len(caches) - len(targets)} 件 {gib(total - removed)}")
    if targets:
        print()
        print("| 理由 | 件数 | 大きさ |")
        print("| --- | --- | --- |")
        by_reason = {}
        for cache, reason in targets:
            count, size = by_reason.get(reason, (0, 0))
            by_reason[reason] = (count + 1, size + cache["size_in_bytes"])
        for reason, (count, size) in sorted(by_reason.items()):
            print(f"| {reason} | {count} | {gib(size)} |")


if __name__ == "__main__":
    main(dry_run="--dry-run" in sys.argv[1:])
