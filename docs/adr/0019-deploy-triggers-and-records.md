# ADR-0019: デプロイの起動条件をリポジトリ側で決め、記録をデプロイの副作用として残す

- status: 承認済み
- 日付: 2026-09-03

## 背景

本番稼働（[ADR-0018](0018-regions.md)）の直後、運用で 2 つの問題が出た。

**1. フロントに関係のない変更でも Vercel のデプロイが走る。**
`main` を Production Branch にしているため、`docs/` だけの変更でも
`backend/` だけの変更でも本番デプロイが発生する。実測でも
docs のみの PR と backend のみの PR が、どちらも Production デプロイを起こしていた。

**2. バックエンドをいつデプロイしたか分からない。**
フロントは Vercel が commit SHA 付きでデプロイ記録を持つが、
バックエンドは `fly deploy` を手で叩く運用のため、
**どのコミットが本番で動いているのかがどこにも残らない**。

## 決定

**ブランチ運用は `main` 単一のまま変えない。** 代わりに次の 2 つを行う。

| 問題 | 対処 | 置き場所 |
| --- | --- | --- |
| 1 | ビルドの起動条件を `ignoreCommand` で決める | `frontend/vercel.json` |
| 2 | デプロイの実行そのものが記録を残す | `scripts/deploy-backend.sh` |

### 1. `ignoreCommand`

```json
"ignoreCommand": "git diff --quiet \"${VERCEL_GIT_PREVIOUS_SHA:-HEAD^}\" HEAD ./"
```

Root Directory（`frontend`）に変更が無ければ終了コード 0 を返し、
Vercel はビルドを飛ばす。変更があれば 1 を返してビルドする。

**比較元は `HEAD^` ではなく「最後に成功したデプロイ」にする。**
`HEAD^` と比べると、**ビルドが失敗した直後に無関係なマージが続いたとき、
失敗したフロントの変更が未デプロイのまま取り残される**（次のマージには
差分が無いのでスキップされる）。`VERCEL_GIT_PREVIOUS_SHA` は
「そのプロジェクトとブランチで最後に成功したデプロイの SHA」で、
**Ignored Build Step を設定したときだけ**渡される。

浅いクローン（`--depth=10`）に SHA が含まれないと `git diff` は
エラー終了するが、**0 以外はビルドする側に倒れる**ので安全側に落ちる。

**Vercel の組み込みスキップ（Skipping unaffected projects）は使えない。**
あれは npm / yarn / pnpm / Bun の **workspaces を前提**としており、
workspace 定義に含まれない変更は「グローバルな変更」として扱われて
全アプリをデプロイする。このリポジトリにはルートの `package.json` も
workspaces も無く、`backend/` は Gradle である。

### 2. デプロイスクリプト

`scripts/deploy-backend.sh` が次を一続きで行う。

1. `main` にいて、作業ツリーが clean で、`origin/main` と一致するか検査
2. **そのコミットの CI が success か検査**
3. `fly deploy --local-only --ha=false --image-label <短縮 SHA>`
4. **マシンが 1 台か検査**（複数なら異常として終了）
5. 成功したときだけ `backend-deploy-<UTC>` タグを打って push

記録は 2 つ残る。

| 記録 | 意味 |
| --- | --- |
| `git tag -l 'backend-deploy-*'` | いつ・どのコミットをデプロイしたか |
| `fly image show -a hatenacal` | **本番で実際に動いているコミット** |

**後者が正本。** タグは「デプロイしたつもり」だが、image label は
動いているものそのものを指す。食い違えば異常が分かる。

## 理由

### 問題 1 はブランチの問題ではない

`release/frontend` を作っても、そこへ `main` をマージすれば
**`backend/` や `docs/` の変更も一緒に流れてくる**ので同じことが起きる。
これは「どのブランチか」ではなく「**ビルドを起動する条件**」の問題であり、
ブランチを増やしても解けない。

### 記録はデプロイの副作用として作る

**ブランチへのマージは「デプロイする意思」の記録であって、
「デプロイした事実」の記録ではない。** マージしてから `fly deploy` を
忘れれば、記録と実態がずれる。**ずれた記録は無い記録より悪い。**
「記録があるから合っているはず」と考えて確認しなくなるためである。

デプロイの成功を条件にタグを打てば、記録は事実になる。

### 付け忘れると課金が倍になる引数がある

`--ha=false` を落とすと HA 用の予備機がもう 1 台作られ、
取り込みジョブが多重起動して同じ範囲を並行取得する。
初回デプロイで実際に起きた。**`fly.toml` では止められず、
このフラグが唯一の制御**なので、コマンドを人の記憶に委ねない。

## 検討した代替案

| 案 | 却下理由 |
| --- | --- |
| `release/frontend` / `release/backend` へマージしてデプロイ | マージ操作が 2 倍になるうえ、**問題 1 を解かない**（上記）。問題 2 も、マージとデプロイの間に人の操作が挟まるため**記録がずれうる** |
| GitHub Actions から `fly deploy` する | 記録は完全になるが、`FLY_API_TOKEN` を GitHub Secrets に置き、第三者アクションを増やす。CI は「**第三者アクションを増やさないため `git diff` で判定する**」と明記しており、それに逆行する。個人開発の規模に対して重い |
| Vercel の Production Branch を `release/frontend` に変える | 変更自体は可能（`Settings → Environments → Production → Branch Tracking`）。ただし上記のとおり問題 1 を解かない |
| Vercel の組み込みスキップを使う | **要件を満たさない**（workspaces 前提） |
| 実行中のコミットをアプリが自分で返す（`/actuator/info` など） | 最も権威があるが、公開する actuator を増やすことになり、`health` 以外を出さない方針（[security.md](../security.md) 第 5 章）を崩す。`fly image show` で同じことが**アプリを変えずに**分かる |

## 結果

- `frontend/vercel.json` に `ignoreCommand` が入った
- `scripts/deploy-backend.sh` が追加された。**`fly deploy` を直接叩かない**
- [CLAUDE.md](../../CLAUDE.md) の Git 運用にデプロイの節が入った
- [runbook-deploy.md](../runbook-deploy.md) の手順がスクリプトに寄った
- **フロントのデプロイ記録は Vercel が持つ**ため、タグは打たない。
  `gh api repos/{owner}/{repo}/deployments?sha=<sha>` で引ける

## 関連

- [ADR-0018](0018-regions.md)（リージョンと本番構成）
- [docs/runbook-deploy.md](../runbook-deploy.md)
- [docs/coding-guidelines.md](../coding-guidelines.md) 第 0.2 節（規約は検査できるなら検査にする）
