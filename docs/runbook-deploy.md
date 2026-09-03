# 手順書 — 本番デプロイ

最終更新: 2026-09-03

関連文書: [architecture.md](architecture.md) 第 7 章・第 8 章・第 11 章 /
[security.md](security.md) / [runbook-x-api-setup.md](runbook-x-api-setup.md) /
[ADR-0009](adr/0009-hosting.md)

---

## 0. この文書の位置づけ

**初回デプロイと、2 回目以降のデプロイの作業手順。** 構成そのものの設計は
[architecture.md](architecture.md) にあり、ここでは繰り返さない。

X API の準備（トークン取得・課金ガードレール）は
[runbook-x-api-setup.md](runbook-x-api-setup.md) が持つ。**先にそちらを済ませておく。**

### 0.1 コンソールの画面名について

Neon / Fly.io / Vercel の画面名は変わる。**入れる値と、なぜそれを入れるかを正とし、
ボタンの名前が違ったら読み替える。**

---

## 1. 前提

### 1.1 デプロイする順番

**Neon → Fly.io → Vercel。** 依存がこの向きだから。

```
Vercel ──BACKEND_BASE_URL──▶ Fly.io ──DATABASE_URL──▶ Neon
```

前を決めないと次の環境変数が埋まらない。**逆順で始めると、
仮の値を入れて後から直すことになり、直し忘れる。**

### 1.2 デプロイするコミット

**`main` の、CI が green のコミットをデプロイする。**

イメージのビルドはコンテナの中で行い、**テストは走らせない**
（結合テストが Testcontainers で PostgreSQL を起動するため、
ビルドコンテナの中では動かせない）。つまり `fly deploy` 自体は
「テストを通っていないコード」も通してしまう。そこは人が守る。

```bash
git checkout main && git pull --ff-only
gh run list --branch main --limit 1     # 直近の CI が success か
git status --short                       # 作業ツリーが汚れていないか
```

**`fly deploy` は作業ツリーの中身を送る。** コミットしていない変更もイメージに入る。

### 1.3 用意しておくもの

| | |
| --- | --- |
| `flyctl` | `curl -L https://fly.io/install.sh \| sh` |
| Fly.io アカウント | 支払い方法の登録が要る（常時起動のため無料枠だけでは動かない） |
| Neon アカウント | Free プラン |
| Vercel アカウント | Hobby プラン。GitHub 連携 |
| Docker | ローカルビルドに使う（動いていなければ Fly.io のリモートビルダーが使われる） |

---

## 2. Neon（DB）

### 2.1 プロジェクトを作る

- リージョンは **`AWS Asia Pacific 1 (Singapore)`**。
  **Neon に東京（日本）リージョンは無い**（[ADR-0018](adr/0018-regions.md)）。
  **作成後に変更できない。** 変えるには別プロジェクトを作ってデータを移行することになる
- PostgreSQL のバージョンは **17**。ローカルの `compose.yaml` と CI に合わせる
- Project name は `hatena-calendar`、Database name は **`hatenacal`**（ローカルと揃える）
- **Neon Auth は off。** 管理者認証は自前で持っている（[architecture.md](architecture.md) 第 3.2 節）

### 2.2 autoscaling の下限を 0.25 CU に固定する

**これを忘れると無料枠の試算が崩れる。**

`Settings → Compute` で **最小と最大の両方を `0.25 CU`** にする。
上振れするとコンピュート時間に比例して CU-hours が増え、
[architecture.md](architecture.md) 第 11 章の「月 31 CU-hours」という見積もりが成立しない。
**無料枠（100 CU-hours/月）を超えると翌請求月までコンピュートが停止し、
サイトが閲覧不能になる**（NFR-02）。

**suspend までの時間は既定（5 分）のままでよい。** 第 11 章の試算はこれが前提。

### 2.3 接続文字列を JDBC の形に直す

**ここが初回デプロイで最も踏みやすい落とし穴。**

Neon が配るのは libpq 形式で、そのままでは Spring から使えない。

```
postgresql://myuser:mypassword@ep-xxx-pooler.ap-northeast-1.aws.neon.tech/hatenacal?sslmode=require
```

**pgjdbc は `user:password@host` の形を受け付けない。** 認証情報は
`user=` / `password=` のクエリパラメータで渡す
（[architecture.md](architecture.md) 第 7.2 節）。

変換後：

```
jdbc:postgresql://ep-xxx-pooler.ap-northeast-1.aws.neon.tech:5432/hatenacal?sslmode=require&user=myuser&password=mypassword
```

直すのは 4 点。

| | 変換 |
| --- | --- |
| スキーム | `postgresql://` → **`jdbc:postgresql://`** |
| 認証情報 | `user:pass@` を削り、末尾に `&user=...&password=...` を足す |
| ポート | `:5432` を明示する |
| ホスト | **`-pooler` が付いたものを選ぶ**（[architecture.md](architecture.md) 第 8 章） |

**パスワードに `/ : @ ( ) [ ] & # = ? ` や空白が含まれていたらパーセントエンコードする。**
生のまま入れるとクエリの区切りとして解釈され、認証に失敗する。
Neon のパスワードを作り直せば済むことも多い。

**`sslmode=require` を落とさない。** Neon は SSL を要求する。

### 2.4 確認

手元から繋がることを先に確かめる。**Fly.io に載せてから間違いに気づくと、
原因がネットワークなのか文字列なのか切り分けられない。**

```bash
psql "postgresql://myuser:mypassword@ep-xxx-pooler.../hatenacal?sslmode=require" -c '\dt'
```

`psql` が無ければ次章の `fly deploy` 後のログで判断する（第 9 章）。

---

## 3. Fly.io（バックエンド）

### 3.1 アプリを作る

`backend/fly.toml` は**コミット済み**。`fly launch` は使わない
（対話でこのファイルを上書きし、`auto_stop_machines` や
`min_machines_running` の指定が消える）。

```bash
cd backend
fly apps create hatenacal        # 名前が取られていたら fly.toml の app も直す
```

**リージョンは `fly.toml` の `primary_region = "sin"`（Singapore）。**
Neon と同居させて、バックエンド↔DB の往復を消す（[ADR-0018](adr/0018-regions.md)）。
`fly launch` の対話で東京を選ばない。

### 3.2 Secrets を入れる

**`fly.toml` に書かない**（[security.md](security.md) T-01）。
`fly secrets set KEY=VALUE` は値がシェル履歴に残るので、標準入力から流す。

必要な変数は [architecture.md](architecture.md) 第 7 章が正本。

| 変数 | 値 |
| --- | --- |
| `DATABASE_URL` | 第 2.3 節で作った JDBC の文字列 |
| `X_BEARER_TOKEN` | [runbook-x-api-setup.md](runbook-x-api-setup.md) 第 4 章 |
| `X_SOURCE_USERNAME` | `xinxin_official` |
| `ADMIN_PASSWORD_HASH` | [architecture.md](architecture.md) 第 7.1 節の手順で作る |
| `INTERNAL_API_KEY` | `openssl rand -base64 32` |
| `INTERNAL_ADMIN_API_KEY` | 同上。**上と別の値**（[ADR-0010](adr/0010-split-api-keys.md)） |

**`DATABASE_USER` / `DATABASE_PASSWORD` は入れない。** 認証情報は
`DATABASE_URL` に含める（第 7.2 節）。

```bash
fly secrets import      # KEY=VALUE を 1 行ずつ貼り、Ctrl-D
fly secrets list        # 名前とダイジェストだけが出る。値は表示されない
```

**ローカルの `.env` をそのまま流し込まない。** ローカル用の値が混ざる。

### 3.3 デプロイ

```bash
cd backend
fly deploy --local-only     # 手元の Docker でビルドする
```

`--local-only` を外すと Fly.io のリモートビルダー（別課金のマシン）が起動する。
Docker が動いているなら手元で焼くほうが速く、余計なマシンも立たない。

初回は JDK イメージの取得と依存のダウンロードで 5〜10 分かかる。

### 3.4 起動を確認する

```bash
fly status                  # 1 台だけが running か
fly logs                    # Flyway の適用と "Started HatenacalApplication"
curl -s https://hatenacal.fly.dev/actuator/health
```

`{"status":"UP"}` が返れば DB まで繋がっている。

**確かめること**

- [ ] マシンが **1 台**（複数だと取り込みが多重起動する）
- [ ] `https://` で応答する（`http://` はリダイレクトされる）
- [ ] `/api/public/appearances?from=...&to=...` が**キー無しで 403**
      （デフォルト拒否。[security.md](security.md) 第 5 章）
- [ ] `/actuator/env` が **403**（health 以外を公開していない）

---

## 4. 本番 DB に情報源アカウントを入れる

**ローカルと本番は別の DB。** `source_account` の行は自動では作られず、
**無いと取り込みが毎回スキップされる**（ログに「情報源アカウントが DB に無い」）。

手順は [runbook-x-api-setup.md](runbook-x-api-setup.md) 第 5.3 節。
本番の接続文字列に対して同じことを行う。

**`last_fetched_tweet_id` は NULL のまま入れる。** 値を入れるとそこから先だけを
取りに行く。初回は過去分の取り込み（バックフィル）が走る。

---

## 5. Vercel（フロントエンド）

### 5.1 プロジェクトを作る

GitHub 連携で `main` を自動デプロイする（[architecture.md](architecture.md) 第 8 章）。

- **Root Directory を `frontend` にする。** モノレポなのでリポジトリ直下ではない
- Framework Preset は Next.js（自動で判定される）

**関数のリージョンは `frontend/vercel.json` が `hnd1`（東京）に指定済み。**
Vercel の既定は **`iad1`（ワシントン DC）**で、**明示しないと日本からの
リクエストが毎回アメリカ経由になる**（[ADR-0018](adr/0018-regions.md)）。
コンソールで上書きしないこと。設定をリポジトリに置いているのは、
コンソール側だと設定漏れに気づけないため。

デプロイ後に `Settings → Functions → Function Regions` が
`Tokyo, Japan (hnd1)` になっていることを確認する。

### 5.2 環境変数

[architecture.md](architecture.md) 第 7 章が正本。**`NEXT_PUBLIC_` を付けない。**
付けるとブラウザに露出する。

| 変数 | 値 |
| --- | --- |
| `BACKEND_BASE_URL` | `https://hatenacal.fly.dev` |
| `BACKEND_API_KEY` | Fly.io の `INTERNAL_API_KEY` と**同じ値** |
| `BACKEND_ADMIN_API_KEY` | Fly.io の `INTERNAL_ADMIN_API_KEY` と**同じ値** |
| `SESSION_SECRET` | `openssl rand -base64 32` |
| `SITE_DISABLED` | 設定しない（停止したいときだけ `true`） |

**左右で変数名が違い、値は同じ**という対応を取り違えやすい。
[architecture.md](architecture.md) 第 7.1 節の対応表を見ながら入れる。

### 5.3 確認

- [ ] `/` が表示され、**カレンダーに出演情報が出る**（バックエンドまで繋がっている）
- [ ] `/2020/01` が **404**（範囲外。[ADR-0014](adr/0014-bounded-calendar-range.md)）
- [ ] フッタに非公式である旨と連絡先が出る（LR-01 / LR-05）
- [ ] `/admin` がログイン画面へ飛び、パスワードでログインできる
- [ ] ログイン後に出演情報を 1 件登録し、公開ページに反映される

```bash
# セキュリティヘッダ（NFR-03 / ADR-0016）
curl -sI https://<domain>/ | grep -iE 'content-security-policy|strict-transport|x-content-type|referrer'
```

---

## 6. デプロイ後にしか確認できないこと

[requirements.md](requirements.md) 第 11 章の DoD のうち、ここで初めて埋まるもの。

- [ ] **NFR-01 応答時間** — Neon のコールドスタートを含めて測る。
      キャッシュ済みの月と、初めて開く月の両方
- [ ] **NFR-04 CU-hours** — 1 週間動かしてから Neon の消費を見る。
      月換算で 100 に対して余裕があるか（試算は約 31）
- [ ] **本番が HTTPS のみで動作する**（[security.md](security.md) 第 5 章）
- [ ] **Neon の autoscaling 下限が 0.25 CU**（第 2.2 節で設定済みのはず。画面で再確認）
- [ ] **NFR-06 / NFR-08** — 実機の幅 360px で横スクロールが出ないか、
      コントラスト比 4.5:1 を満たすか

**取り込みが動くことの確認は 30 分待つ。** `initial-delay` が 1 分、
`interval` が 30 分。管理画面の「取り込み状況」に成功が記録されれば動いている。

---

## 7. 止める・戻す

| やりたいこと | 手段 |
| --- | --- |
| **サイトを非公開にする**（LR-05） | Vercel の `SITE_DISABLED=true` → 再デプロイ。停止中も `/admin` は使える（[ADR-0013](adr/0013-site-kill-switch.md)） |
| 取り込みだけ止める | Fly.io に `X_INGESTION_ENABLED=false` → `fly deploy` |
| 前のバージョンへ戻す | `fly releases` で番号を見て `fly deploy --image <前のイメージ>`、または Vercel の Deployments から Promote |
| 全部止める | Fly.io のマシンを停止。**ただし Vercel の ISR キャッシュは配信され続ける**（[ADR-0013](adr/0013-site-kill-switch.md)） |

**バックエンドを止めても公開ページは消えない。** 止めたいときは `SITE_DISABLED`。

---

## 8. 2 回目以降

```bash
git checkout main && git pull --ff-only
gh run list --branch main --limit 1        # CI が green か
cd backend && fly deploy --local-only      # バックエンドを変えたときだけ
```

フロントエンドは `main` への push で Vercel が自動デプロイする。手作業は要らない。

**マイグレーションを足したときは、起動時に Flyway が適用する。**
`fly logs` で適用を確認してから公開ページを触る。

---

## 9. うまくいかないとき

| 症状 | 見るところ |
| --- | --- |
| 起動時に `FATAL: password authentication failed` | `DATABASE_URL` の形式（第 2.3 節）。`user=` / `password=` をクエリで渡しているか。パスワードの記号をエンコードしたか |
| 起動時に `Connection refused` / タイムアウト | ホストが `-pooler` 付きか。`sslmode=require` が付いているか |
| Flyway が `Validate failed` | ローカルと本番でマイグレーションの履歴が食い違っている。**本番の `flyway_schema_history` を直接消さない**。原因を特定してから判断する |
| 公開ページが 500 | `BACKEND_BASE_URL` の綴り、`BACKEND_API_KEY` と `INTERNAL_API_KEY` の値が一致しているか |
| 公開ページが空だが 200 | バックエンドには繋がっているがデータが無い。本番 DB に出演情報が入っていない（第 4 章） |
| 管理画面だけ 403 | `BACKEND_ADMIN_API_KEY` と `INTERNAL_ADMIN_API_KEY` の不一致。**公開キーと取り違えていないか**（[ADR-0010](adr/0010-split-api-keys.md)） |
| 取り込みが動かない | `source_account` の行があるか（第 4 章）。`X_BEARER_TOKEN` が入っているか。`fly logs` に「スキップする」が出ていないか |
| ヘルスチェックが通らず入れ替わらない | `grace_period` より起動が遅い。`fly logs` で Flyway の適用時間を見る |

**課金が跳ねたときは `last_fetched_tweet_id` をまず疑う**
（[runbook-x-api-setup.md](runbook-x-api-setup.md) 第 7 章）。
