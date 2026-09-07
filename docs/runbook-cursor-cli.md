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

```bash
agent -p --trust --output-format text "…指示…"
```

| フラグ | 意味 |
| --- | --- |
| `-p` / `--print` | 非対話。これが無いと TUI が開く |
| `--trust` | ワークスペースを信頼する。無いと毎回止まる |
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

もっと強く隔離するなら次がある。**まだ使っていない。**

- `agent -w`（`--worktree`）で隔離した git worktree に入る。
  追跡されていないファイルは複製されないので、`.env` がそもそも存在しなくなる
- `.cursor/sandbox.json` でネットワークを既定拒否にする

## 5. 課金

**CLI の利用は Cursor のプランの枠を食う**（エディタと同じ財布）。
CLI には Auto モードが無く、**全リクエストが従量枠を消費する**。
X API の課金とは別枠だが、実装をまるごと投げると効く。

## 未決定事項

- **worktree（`agent -w`）に切り替えるか。** `.env` を物理的に届かなくできる。
  バックエンドを動かす作業には向かないが、`frontend/` の作業なら支障がないはず
- **`~/.cursor/cli-config.json`（全体設定）を置くか。** モデルの既定値と
  コミット・PR への署名の扱いがここにある
