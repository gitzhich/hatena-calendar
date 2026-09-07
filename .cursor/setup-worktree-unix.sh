#!/usr/bin/env bash
# 新しい worktree を frontend の作業ができる状態にする。
#
# **.env を複製しない。** 追跡外のファイルが入らないことが worktree を使う理由で、
# 公式の例にある `cp $ROOT_WORKTREE_PATH/.env .env` は目的を潰す
# （docs/runbook-cursor-cli.md「実装させるときは worktree に入れる」）。
set -euo pipefail

root="${ROOT_WORKTREE_PATH:-}"

# node_modules は追跡外なので worktree には無い。ロックが同じなら本体から借りる。
# 違うならこの worktree 用に入れ直す（借りると古い依存で緑になってしまう）。
if [ -n "$root" ] &&
	[ -d "$root/frontend/node_modules" ] &&
	cmp -s frontend/package-lock.json "$root/frontend/package-lock.json"; then
	ln -s "$root/frontend/node_modules" frontend/node_modules
	echo "node_modules を本体から借りた: $root/frontend/node_modules"
else
	(cd frontend && npm ci)
fi
