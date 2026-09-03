#!/usr/bin/env bash
#
# バックエンドを Fly.io へデプロイし、記録を残す。
#
# なぜスクリプトにしたか
# ----------------------
# 手で `fly deploy` を打つ運用には 2 つの穴があった。
#
# 1. **--ha=false を付け忘れるとマシンが 2 台立つ。** 初回デプロイで実際に
#    起きた。2 台になると取り込みジョブが多重起動し、同じ範囲を並行して
#    取得して X API の課金が倍になる（fly.toml では止められない）
# 2. **いつ・どのコミットをデプロイしたか、どこにも残らない。** フロントは
#    Vercel が記録を持つが、バックエンドは手動デプロイのため記録が無かった
#
# 記録は**デプロイの副作用として作る**。人が別途行う操作にすると必ず忘れ、
# 記録と実態がずれる。**ずれた記録は無い記録より悪い**（ADR-0019）。
#
# 残る記録は 2 つ。
#
#   git tag -l 'backend-deploy-*'   いつ・どのコミットをデプロイしたか
#   fly image show -a hatenacal     **本番で実際に動いているコミット**
#
# 後者が正本。タグは「デプロイしたつもり」だが、image label は
# 動いているものそのもの。食い違えば異常が分かる。

set -euo pipefail

APP=hatenacal
BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../backend" && pwd)"
FLY="${FLYCTL:-fly}"
ASSUME_YES=0

[ "${1:-}" = "-y" ] && ASSUME_YES=1

die() { printf '\n中止: %s\n' "$1" >&2; exit 1; }

command -v "$FLY" >/dev/null 2>&1 || die "$FLY が見つからない。PATH を通すか FLYCTL= で指定する"

# --- デプロイしてよい状態か ------------------------------------------------

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "main" ] || die "main にいない（現在: $branch）。デプロイは main から行う"

[ -z "$(git status --porcelain)" ] || die "作業ツリーが汚れている。fly deploy は**コミットしていない変更も送る**"

git fetch --quiet origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
    || die "origin/main と一致しない。先に pull / push する"

sha=$(git rev-parse HEAD)
short=$(git rev-parse --short HEAD)

# **CI が緑のコミットだけをデプロイする。** イメージのビルドはコンテナの中で
# 行い、テストは走らせない（Testcontainers が Docker を要求するため）。
# つまり fly deploy 自体は「テストを通っていないコード」も通してしまう。
conclusion=$(gh api "repos/{owner}/{repo}/commits/$sha/check-runs" \
    --jq '[.check_runs[] | select(.name=="ci")] | .[0].conclusion // "なし"' 2>/dev/null || echo "取得失敗")
[ "$conclusion" = "success" ] || die "このコミットの CI が success ではない（$conclusion）"

# --- 確認 -----------------------------------------------------------------

printf 'デプロイ対象\n'
printf '  アプリ  : %s\n' "$APP"
printf '  コミット: %s  %s\n' "$short" "$(git log -1 --format='%s' | cut -c1-60)"
printf '  CI      : success\n\n'

if [ "$ASSUME_YES" -eq 0 ]; then
    read -r -p "デプロイする？ [y/N] " answer
    [ "$answer" = "y" ] || die "実行しなかった"
fi

# --- デプロイ --------------------------------------------------------------

cd "$BACKEND_DIR"

# --ha=false が要る。付けないと HA 用の予備機がもう 1 台作られる。
# --image-label に SHA を入れることで、本番で動いているコミットを
# `fly image show` から辿れる（既定は deployment-{timestamp} で追えない）
"$FLY" deploy --local-only --ha=false --image-label "$short" -a "$APP"

# --- 台数を確かめる --------------------------------------------------------

machines=$("$FLY" machines list -a "$APP" --json | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
if [ "$machines" -ne 1 ]; then
    printf '\n★ マシンが %s 台ある。取り込みが多重起動して課金が倍になる。\n' "$machines" >&2
    printf '  fly scale count 1 -a %s で 1 台に落とすこと。\n' "$APP" >&2
    exit 1
fi

# --- 記録 ------------------------------------------------------------------

# システムの時刻は UTC で残す（docs/data-model.md 第 6 章）。
# Z を付けてタイムゾーンの読み違えを防ぐ
tag="backend-deploy-$(date -u +%Y%m%dT%H%MZ)"
git tag "$tag" "$sha"
if git push --quiet origin "$tag"; then
    printf '\n完了\n'
else
    printf '\n完了（ただしタグの push に失敗した。手で `git push origin %s` すること）\n' "$tag" >&2
fi
printf '  マシン    : 1 台\n'
printf '  タグ      : %s -> %s\n' "$tag" "$short"
printf '  動作確認  : fly image show -a %s  で %s が出ることを確かめる\n' "$APP" "$short"
