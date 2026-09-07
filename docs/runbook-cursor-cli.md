# 手順書 — Cursor CLI を Claude Code から動かす

実装を Cursor に投げ、レビューを Claude Code が行う（[CLAUDE.md](../CLAUDE.md)「エージェントの役割分担」）。
その Cursor を**エディタではなく CLI から**動かすための手順。

設計ではなく手順書。**なぜその権限にしたか**は本書「権限の設計」にある。

## 1. 導入（1 回だけ）

```bash
curl https://cursor.com/install -fsS | bash        # ~/.local/bin に入る
agent --version                                     # 2026.09.02-c22c1a3 で確認
NO_OPEN_BROWSER=1 agent login                       # WSL はこれが要る
agent status                                        # Logged in as ... を確認
```

コマンド名は `agent`。`cursor-agent` は旧名のエイリアス。

**`NO_OPEN_BROWSER=1` を省かない。** この WSL には `xdg-open` が無く（`wslview` のみ）、
付けないとブラウザを開けずに止まる。URL が表示されるので Windows 側で開く。

`~/.local/bin` が PATH に無ければ通す。

## 2. 呼び出し方

**調査だけさせるとき**（読み取り専用。作業ツリーに触らない）:

```bash
agent -p --trust --mode ask --model cursor-grok-4.6-high --output-format text "…質問…"
```

**実装させるとき**（本書「実装させるときは worktree に入れる」）:

```bash
agent -p --trust -w <名前> --model cursor-grok-4.6-high --output-format text "…指示…"
```

| フラグ | 意味 |
| --- | --- |
| `-p` / `--print` | 非対話。これが無いと TUI が開く |
| `--trust` | ワークスペースを信頼する。無いと毎回止まる |
| `--model` | 本書「モデル」。**省くと `auto` になる** |
| `-w <名前>` / `--worktree` | 隔離した worktree に入る。**実装では必ず付ける** |
| `--output-format` | `text` / `json` / `stream-json` |
| `--mode ask` / `--plan` | 読み取り専用。調査だけさせるとき |
| `--force` / `--yolo` | **付けない**（後述） |

`--output-format stream-json` は**どのツールを呼んだか**が出る。
挙動を疑うときはこれで見る。`agent ls` / `agent resume` で会話を継続できるので、
レビュー指摘は同じ文脈へ返せる。

## 3. 権限の設計

権限は `.cursor/cli.json`（プロジェクト）と `~/.cursor/cli-config.json`（全体）。
**プロジェクト側で設定できるのは権限だけ**で、他の設定は全体側にしか置けない。

### 実測で分かったこと

公式ドキュメントだけでは足りず、**書いたつもりで効いていない設定になる**。
以下は 2026-09-07 に実際に試した結果。

| 事実 | 意味 |
| --- | --- |
| **パターンは絶対パスに照合される** | `Read(docs/security.md)` は**効かない**。`Read(**/security.md)` は効く。リポジトリ相対で書くと**黙って無効になる** |
| `Write(**)` は一致しない | `Write(**/*)` か `Write(**/frontend/**)` のように書く |
| deny は allow より優先 | `Read(**/*)` を許しても `Read(**/.env)` の拒否が勝つ |
| **`--force` を付けるとシェルが開く** | `Write` の拒否をシェルで迂回された。編集ツールが拒否されたあと `printf … >> file` で書き込んだ（`stream-json` で確認） |
| `--force` なしなら許可リスト外のシェルは拒否 | しかも**非対話のまま完走する**（exit 0）。承認待ちで止まらない |
| `Write(**/path)` を allow に入れれば `--force` なしで編集できる | 「シェルは閉じたまま、ファイル編集だけ許す」が成立する |
| プロジェクトの `cli.json` に `version` キーは置けない | 公式の例は全体側 `cli-config.json` のもの。`permissions.allow` は必須 |

### 従うべき結論

- **`--force` を使わない。** 使うと拒否がシェルで迂回できてしまう
- **シェルは許可リストで絞る。** `cat` / `grep` / `sed` / `python3` / `bash` のような
  **任意のファイルを読める汎用コマンドを allow に入れない**。読むのは Read ツールに
  やらせる。そちらは deny が効く
- 拒否は「防御」、許可は「境界」。**両方書く**

### いま入れているもの

`backend/` を書けなくしてあるのは、[AGENTS.md](../AGENTS.md) が挙げる「実装せずに報告すること」を
機械的に強制するため。`frontend/` の中でも CSP・認証・年月範囲に関わる
ファイルは拒否側に置いている（[frontend/AGENTS.md](../frontend/AGENTS.md) が挙げる「触らないもの」と対応）。

`fly` / `gh pr merge` / `gh release` の拒否は、
[AGENTS.md](../AGENTS.md) が挙げる「絶対にしないこと」のうち機械化できるものを移したもの。

## 4. これは砂場ではない

**許可したコマンドの内側までは見ていない。** `git show HEAD:<path>` は
追跡されているファイルなら何でも出せるし、`npm run <script>` は
`package.json` に書かれた任意のコマンドを走らせる。

**`.cursorignore` はさらに弱い。** 公式が「Agent が使うターミナルと MCP サーバの
ツールは `.cursorignore` の対象コードへのアクセスを遮断できない」「完全な保護は
保証されない」と明記している。索引化を減らすものであって、禁止ではない。

だからシークレットについては、権限に頼らず**そもそも置かない**——次章。

`.cursor/sandbox.json` でネットワークを既定拒否にする手もある。**まだ使っていない。**

## 5. 実装させるときは worktree に入れる

`-w <名前>` を付けると `~/.cursor/worktrees/hatena-calendar/<名前>` に
隔離した worktree を作り、そこで作業する。**実装では必ず付ける。**

**追跡されていないファイルは複製されない。** シークレットの実体が
worktree に存在しないので、拒否をシェルで迂回されても読めるものが無い。
権限は「読ませない」だが、こちらは「置かない」で、質が違う。

`.cursor/worktrees.json` が `.cursor/setup-worktree-unix.sh` を呼び、
`frontend/node_modules` を本体から借りる（ロックが一致するときだけ。
違えば `npm ci` で入れ直す）。**`.env` は複製しない。**
公式の例に `cp $ROOT_WORKTREE_PATH/.env .env` があるが、これは目的を潰す。

```bash
agent -p --trust -w day-nav --model cursor-grok-4.6-high --output-format text "…"
git worktree list                       # どこに何があるか
```

**追跡されているものは worktree にも入る。** `docs/x-post-sample/` は
追跡下にあるので複製される。あれを守っているのは `.cursor/cli.json` の
拒否だけで、前章の限界がそのまま当てはまる。

作業が終わったら worktree とブランチを片付ける。**本体のブランチに
取り込んでからにすること。**

## 6. モデル

**`--model` を省くと `auto`。** 明示する。

```bash
agent models                    # このアカウントで使えるものを一覧する
```

実装は **`cursor-grok-4.6-high`**（一覧では `Cursor Grok 4.6`）を既定にする。
軽い調査なら `cursor-grok-4.6-medium`、難しい変更なら `cursor-grok-4.6-xhigh`。
`-fast` の付いたものは同じモデルの高速版。

レビューは Claude Code が別プロセスで行うため、ここで Claude 系を選ばない。
**実装とレビューを別の目で行うのが役割分担の目的**で、同じモデルにすると
それが崩れる（[CLAUDE.md](../CLAUDE.md)「エージェントの役割分担」）。

## 7. 課金

**CLI の利用は Cursor のプランの枠を食う**（エディタと同じ財布）。
X API の課金とは別枠だが、実装をまるごと投げると効く。

`agent models` の既定は `auto` で、CLI にも Auto はある。
**ただし `--model` で明示すると当然その分が乗る。**
枠の消費のされ方までは確かめていない。

## 未決定事項

- **`~/.cursor/cli-config.json`（全体設定）を置くか。** モデルの既定値と
  コミット・PR への署名の扱いがここにある。いまは呼び出しごとに `--model` を
  渡している（設定はリポジトリに入らないため、手順書が正本になる）
- **`.cursor/sandbox.json` を入れるか。** ネットワークを既定拒否にできる
