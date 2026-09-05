# 手順書 — X API の実キー設定

最終更新: 2026-09-05

関連文書: [CLAUDE.md](../CLAUDE.md) / [x-integration.md](x-integration.md) /
[architecture.md](architecture.md)「設定と環境変数」 / [security.md](security.md)「X API トークンの漏洩」

---

## 0. この文書の位置づけ

X API の Bearer Token を取得し、ローカルと Fly.io に設定して疎通を確認するまでの**作業手順**。
設計判断そのものは各設計文書にあり、ここでは繰り返さない。

**この手順の途中から実費が発生する。** 手順 3（課金ガードレール）を
手順 4（トークン取得）より**先に**行うこと。順序を入れ替えない。
トークンが存在した瞬間から、実装の不具合や設定ミスが金銭的損害に変わる。

### 0.1 裏取りの時点と、古い情報の見分け方

本書は **2026-09-02** に X の公式文書（`docs.x.com`）で確認した内容にもとづく。

X API の料金体系は **2026-02-06 に従量課金（pay-per-usage）へ移行**した。
それ以前に書かれた記事は Free / Basic / Pro の月額プランを前提にしており、
**現在は通用しない**。特に次の記述を見たら、その記事は古い。

- 「Free tier で月 1,500 ツイート取得できる」
- 「Basic は月 $200」「Pro は月 $5,000」
- 入口が `developer.x.com` の Developer Portal だけ、という説明

現在の入口は **`console.x.com`（Developer Console）**。
新規登録は従量課金のみで、**無料プランは選択肢に存在しない**。

なお、二次情報には**月間上限を 200 万リソースとする記述が複数ある**が、
公式文書の記載は **300 万 Post 読み取り / 請求サイクル**である。本書は公式に従う。

### 0.2 着手前に決めておくこと

以下は本手順の中で値が必要になる。**未決定のまま進めると手順 5 で止まる。**
2026-09-02 時点ですべて決着している。

| # | 決めること | 参照 | 状態 |
| --- | --- | --- | --- |
| 1 | 情報源アカウントの X ハンドル | [requirements.md](requirements.md) FR-40 | **確定**: `@xinxin_official` |
| 2 | 月あたりの支出上限（ガードレールに設定する値） | 本書 手順 3 | **確定**: $5 |
| 3 | 課金に使う支払い方法 | — | **確定** |

### 0.3 手作業で行う箇所

| 箇所 | 状態 | 備考 |
| --- | --- | --- |
| `XApiClient` / 取り込み | **実装済み**（FR-40〜43） | 手順 5 の疎通確認は `curl` で行う。設定を疑うとき、アプリを通さずに切り分けられる |
| ユーザー ID の解決（[x-integration.md](x-integration.md)「ユーザー ID の解決（初回のみ）」） | **実装済み** | ただし 1 回 $0.010 かかるため、**手順 5.2 で手で叩いた結果を DB に入れる**運用にしている |
| `source_account` への投入 | **投入手段なし** | 手順 5.3 で SQL を直接実行する。管理 API には無い |
| 取り込みの停止スイッチ | **実装済み**（`X_INGESTION_ENABLED`） | 反映にデプロイが要る点は未解決（[security.md](security.md)「未決定事項」） |
| 打ち切り（連続 10 回失敗）からの復帰 | **戻す手段なし** | 本書「打ち切りから戻す」で SQL を直接実行する。押すだけで戻せると原因が残ったまま再開するため、管理 API には置かない |

---

## 1. 前提条件

| 項目 | 内容 |
| --- | --- |
| X アカウント | 開発者登録に使うもの。**電話番号の認証を済ませておく** |
| 支払い方法 | クレジットカード。従量課金のため必須 |
| ローカル | `curl`、`psql`（または `docker compose exec`）、`jq`（任意） |
| Fly.io | `flyctl` がインストール済みで、アプリが作成済みであること |

**このリポジトリは外部公開しない**（[ADR-0015](adr/0015-private-repository.md)）が、
それはシークレットをコミットしてよいという意味ではない。
`.gitignore` は `.env*` を除外済み（`.env.example` のみ例外）。

---

## 2. 開発者アカウントと App を作る

1. `console.x.com` に X アカウントでサインインする
2. Developer Agreement and Policy に同意する
3. 利用目的を記入する。**このアプリの位置づけをそのまま書く**
   （非公式のファンサイト、公式アカウントの公演告知を集約して
   カレンダー表示する、投稿本文は転載せず事実データに正規化して保持する）
4. App を作成する

**App と Project の関係**: コンソールの説明どおり、
**App は「鍵の束」で、Project に接続することで何を呼べるかが決まる**。
既定では `Default Project` に接続され、プランは `Pay Per Use` になる。
App を複数持っても課金は増えないが、**鍵は App ごとに別物**である。
どの App の鍵を使っているかを取り違えないこと。

App 作成後の認証設定について:

- **User authentication settings は設定しない。** 本アプリは
  OAuth2 App-Only（読み取り専用）しか使わない。
  Client ID / Client Secret はユーザー文脈の OAuth 2.0 用であり、**本アプリでは使わない**
- 権限は **Read のみ**。書き込み権限を付けない
  （投稿作成は $0.015／URL 込みは $0.200 と桁が違う。事故時の損害を作らない）

---

## 3. 課金ガードレールを設定する

**トークンを取る前にここを終わらせる。**
このプロジェクトの想定コストは **月 $1.5 程度**（[x-integration.md](x-integration.md)「想定コスト」）で、
桁が 2 つ違う請求は必ず異常である。異常を「止まる」形にしておく。

### 3.1 なぜ先にやるのか

X の従量課金は**クレジットの前払い**で、残高が閾値を割ると
**自動チャージ（auto-recharge）が走る**。移行アカウントの既定値は
**残高が $10 を割ると $200 を追加購入**し、請求サイクルあたりの上限が **$400** である。
月 $1.5 の用途にこの設定は過大で、暴走時の損害がそのまま 2 桁変わる。

さらに、自動チャージには**引っかかりやすい仕様**がある。

> 閾値と同額の残高は「発火可能」とみなされる。
> $5 の閾値に対して $5 を購入すると、**最初の 1 回の消費で追加購入が走る**。

閾値は購入額より**十分低く**設定すること。

### 3.2 設定する値

`console.x.com` の左サイドバー **請求書作成**（クレジット / 支払い / 請求情報）で設定する。

| 設定 | 推奨値 | 理由 |
| --- | --- | --- |
| 初回購入額 | $10 | 月 $1.5 想定なら半年分。手順 4・5 の疎通確認込みでも十分 |
| 自動チャージ | **無効** | 月 $1.5 の用途で自動追加購入は不要。残高切れは事故ではなく検知手段 |
| （有効にする場合）追加額 | $10 | 既定の $200 は使わない |
| （有効にする場合）発火閾値 | $2 | 購入額より十分低くする（本書「なぜ先にやるのか」の仕様） |
| 請求サイクルの支出上限 | **$5** | 想定の 3 倍強。超えたら実装かトークンが壊れている |

**$5 はバックフィルを含めても足りた。** 2026-09-03 の初回バックフィルは
338 リソース / **$1.69**（付録 C）。想定していた「バックフィルの月は一時的に
超えるかもしれない」は起きなかったので、この値のままにする。

自動チャージ側の安全弁として、X 側にも
**「5 分に 1 回まで」「残高が 0 以下になると停止」**という制限がある。
ただしこれは*連鎖的な多重チャージ*を防ぐものであり、上限そのものではない。

**自動チャージを無効にすると、損失が残高で頭打ちになる。**
残高 $10・請求サイクル上限 $5 なら、最悪でも 1 サイクル $5、
累計でも $10 を超えて請求されることがない。**2 つの設定は掛け算で効く。**

### 3.3 コンソールで紛らわしい点

- **Grok API のクレジットは別勘定。** クレジット画面の上部に
  「Grok API のクレジットは別の場所で購入します」と出る。
  xAI コンソール側の残高を X API の残高と読み違えない
- **無料クレジットバウチャー**の引き換え欄がある。X が配布することがあるもので、
  自分で購入する必要はない。**バウチャーが無ければ「無料クレジット」は $0.00 のまま**で、
  それは異常ではない
- 請求サイクルは**購入日起点**で切られる（例: `Sep 2 - Oct 2`）。
  暦月ではないため、支出上限のリセット日を月初と思い込まない。
  **起点の日をアプリ側の設定 `x.billing-cycle-start-day` に入れる**
  （[api.md](api.md)「取り込み履歴」）。ずれていると管理画面の合計が
  支出上限のリセットと噛み合わない

### 3.4 支出上限だけを信用しない

開発者フォーラムには、**支出上限と残高マイナスが 1 週間以上にわたって
効かず、リクエストが通り続けた**という報告がある。
上限設定は「効いたら儲けもの」の位置づけで扱い、**検知を自前で持つ**。

| 層 | 手段 | 状態 |
| --- | --- | --- |
| X 側 | 請求サイクルの支出上限 | 手順 3.2 で設定。**単独では信用しない** |
| X 側 | 自動チャージを無効化 | 手順 3.2。残高が尽きれば止まる |
| アプリ側 | `ingestion_run.fetched_resource_count` の請求サイクル合計を管理画面に出す | [security.md](security.md) T-01 |
| アプリ側 | 連続失敗で取り込みを打ち切る | [x-integration.md](x-integration.md)「エラーハンドリング」 |
| 運用 | 使用量エンドポイントの定期確認 | 本書 手順 7 |

---

## 4. Bearer Token を取得して置く

### 4.1 取得

Developer Console の Keys and tokens から Bearer Token を生成する。
**表示は 1 回だけ**で、閉じると二度と見られない。失った場合は再生成する
（再生成すると**古いトークンは無効になる**）。

API Key / API Secret Key を持っている場合は、次でも同じものが得られる。

```bash
curl -u "$API_KEY:$API_SECRET_KEY" \
     --data 'grant_type=client_credentials' \
     'https://api.x.com/oauth2/token'
```

`access_token` フィールドが Bearer Token である。

### 4.2 置き場所

**トークンをコマンドライン引数に書かない。** シェル履歴に残る。
チャット・Issue・スクリーンショットにも貼らない。

ローカルはリポジトリ直下の `.env`（`.gitignore` 済み）に置く。雛形から作る。

```bash
cp .env.example .env
```

`X_BEARER_TOKEN` と `X_SOURCE_USERNAME` を含む全キーが、
**値を伏せた状態で `.env.example` に登録済み**（[architecture.md](architecture.md)「設定と環境変数」）。
つまり `.env` には**空の行が既にある**。

**追記（`>>`）してはいけない。同じキーが 2 行になる。**
既存の行を置き換える。もっとも確実なのは**エディタで開いて空欄に貼る**こと。
トークンが手元にある以上、入力を隠しても得るものはない。

ターミナルで完結させたい場合は、追記ではなく置換にする。

```bash
read -rs -p 'X_BEARER_TOKEN: ' TOKEN; echo
grep -v '^X_BEARER_TOKEN=' .env > .env.tmp \
  && printf 'X_BEARER_TOKEN=%s\n' "$TOKEN" >> .env.tmp \
  && mv .env.tmp .env
unset TOKEN
```

`read` は**標準入力から**読むため、値がシェル履歴に残らない。`-s` は画面にも出さない
（スクロールバックとスクリーンショットへの写り込みを防ぐ）。`.env.tmp` は
`.gitignore` の `.env.*` に当たる。

**`.env.example` は空のまま保つこと。**

### 4.2.1 `.env` を読むのは誰か

| 読む主体 | 何を読むか |
| --- | --- |
| `./gradlew bootRun` | **リポジトリ直下の `.env`** を読み、**環境変数として** Spring Boot に渡す（`backend/build.gradle.kts`）。**空の値は渡さない** |
| Spring Boot 本体 | プロセスの環境変数のみ。`.env` は読まない |
| `./gradlew test` | **読まない。** DB も使い捨てのコンテナを使う（[CLAUDE.md](../CLAUDE.md)） |
| Next.js | `frontend/.env.local`。**リポジトリ直下の `.env` は読まない** |
| `docker compose` | **読む。** 意図した動作ではない（下記） |

**`docker compose` が `.env` を読み、値の中の `$` を変数展開として扱う。**
`ADMIN_PASSWORD_HASH` の `$2b$12$...` がこれに当たり、次のような警告が出る。

```
warning: The "NJI4tmk..." variable is not set. Defaulting to a blank string.
```

`compose.yaml` は `.env` の値を一切使っていないため**実害はない**が、
**ハッシュの一部がコンソールに出る**。塩の一部であって秘密ではないものの、
ノイズになる。`bootRun` が読む値には影響しない（そちらは展開しない）。

手順 5 でシェルに読み込むときは **`X_` だけに絞る**（理由は本書「疎通を確認する」冒頭）。

### 4.3 置いた直後に確認する

```bash
git status --porcelain          # .env が現れないこと
git check-ignore -v .env        # .gitignore に当たっていること
grep -n '^X_' .env.example      # 2 キーとも = の右が空であること
```

`.env.example` に値が入っていたら**それは事故**。ここは常に空でなければならない。

---

## 5. 疎通を確認する

**ここから課金が発生する。** 各段階の費用を明記する。単価は
[x-integration.md](x-integration.md)「単価（2026 年時点）」。

以降 `$X_BEARER_TOKEN` は `.env` から読み込んだ値とする。
**`X_` で始まる行だけを読み込む。**

```bash
set -a; . <(grep '^X_' ./.env); set +a
```

**`.env` を丸ごと読み込まない**（`. ./.env`）。雛形由来の `.env` は
`DATABASE_URL=` などが**空文字**で入っている。Spring の
`${DATABASE_URL:jdbc:postgresql://localhost:5432/hatenacal}` は変数が
**未設定のとき**だけデフォルトを使うため、空文字を環境に置くと
デフォルトが効かず、同じシェルでの `./gradlew bootRun` が接続に失敗する。

### 5.1 トークンの有効性を確認する（$0）

**Post を 1 件も読まないので課金されない。** 最初にこれを叩く。

```bash
curl -sS -w 'HTTP %{http_code}\n' \
     -H "Authorization: Bearer $X_BEARER_TOKEN" \
     'https://api.x.com/2/usage/tweets'
```

```json
{"data":{"cap_reset_day":2,"project_cap":"3000000",
         "project_id":"...","project_usage":"0"}}
```

| 返り値 | 意味 |
| --- | --- |
| `200` | **トークンは有効。** 認証が通っている |
| `401` | トークンが誤っているか、無効化されている |
| `403` | App が Project に接続されていない可能性がある |

- `project_cap` は請求サイクルあたりの Post 読み取り上限（`3000000`）
- `project_usage` は当サイクルの消費数。**このエンドポイント自体は加算されない**
- `cap_reset_day` は上限がリセットされる日。請求サイクルの起点と一致する

**日次の内訳が要るときは `usage.fields` を指定する。** 既定では返らない。

```bash
curl -sS -H "Authorization: Bearer $X_BEARER_TOKEN" \
  'https://api.x.com/2/usage/tweets?days=7&usage.fields=daily_project_usage'
```

`days` は 1〜90（既定 7）。`usage.fields` に指定できるのは
`cap_reset_day` / `daily_client_app_usage` / `daily_project_usage` /
`project_cap` / `project_id` / `project_usage`。

**`GET /2/usage/credits` はこの環境では 404 が返る（原因未特定）。**
API 自身の仕様には存在するため、文書の誤りではない。詳細は付録 C.2。
残高はコンソールの「請求書作成 → クレジット」で見る（本書「コンソールで紛らわしい点」）。

### 5.1.1 API の仕様は API 自身から取れる

```bash
curl -sS -H "Authorization: Bearer $X_BEARER_TOKEN" \
     'https://api.x.com/2/openapi.json'
```

**課金されない。** 稼働中の API と同じバージョンの OpenAPI 仕様が返るため、
`docs.x.com` の記述と実挙動が食い違ったときの**一次情報**になる。
パス・パラメータ・認証方式・必須項目が機械可読な形で入っている。

### 5.2 ユーザー ID を解決する（$0.010）

**一度だけ叩く。結果を DB に永続化し、二度と呼ばない**（FR-40 /
[x-integration.md](x-integration.md)「ユーザー ID の解決（初回のみ）」）。

```bash
curl -s -H "Authorization: Bearer $X_BEARER_TOKEN" \
     "https://api.x.com/2/users/by/username/$X_SOURCE_USERNAME"
```

返った `data.id` が `source_account.x_user_id` に入る値。

### 5.3 `source_account` に投入する

`x_user_id` は `NOT NULL` なので、行がないと取り込みは動かない。

```sql
INSERT INTO source_account (username, x_user_id)
VALUES ('xinxin_official', 1907616831361396737);
```

`last_fetched_tweet_id` は **`NULL` のままにする**。
初回は未設定として扱われ、`start_time` で範囲を限定したバックフィルになる
（[x-integration.md](x-integration.md)「初回バックフィル」）。

ローカルの実行例:

```bash
docker compose up -d --wait     # Docker Desktop を先に起動しておく
docker compose exec -T db psql -U hatenacal -d hatenacal \
  -c "INSERT INTO source_account (username, x_user_id) \
      VALUES ('xinxin_official', 1907616831361396737);"
```

### 5.4 投稿の取得を確認する（最大 $0.025）

`max_results=5` に絞る。**確認のために 100 件取らない。**

```bash
curl -s -H "Authorization: Bearer $X_BEARER_TOKEN" \
     "https://api.x.com/2/users/<x_user_id>/tweets?max_results=5&exclude=replies,retweets&tweet.fields=created_at,note_tweet"
```

確認する点:

- `data` に投稿が返ること
- 長文投稿で `note_tweet.text` が入ること。`text` は 280 文字で切れる
  （[x-integration.md](x-integration.md)「本文の取り出し」）
- フィールド指定は **`tweet.fields`** である。`post.fields` ではない
  （公式文書の呼称は「Post」に変わったが、**クエリパラメータ名は `tweet.fields` のまま**）

### 5.5 確認できたら課金額を照合する

```bash
curl -s -H "Authorization: Bearer $X_BEARER_TOKEN" 'https://api.x.com/2/usage/tweets'
```

`project_usage` が、手順 5.4 で返った件数と一致すること。
**課金はリクエスト数ではなく返却リソース数に対して発生する**ため、
ここがずれるなら課金モデルの理解が誤っている。

手順 5 全体の実費は **$0.035 以下**。

---

## 6. Fly.io に載せる

**デプロイ全体の手順は [runbook-deploy.md](runbook-deploy.md)。** ここには
X API 固有のことだけを書く。

`fly.toml` に書かない。**Secrets に入れる**（[security.md](security.md) T-01）。
`fly secrets set KEY=VALUE` は値がシェル履歴に残るので、標準入力から流し込む。

```bash
# .env から X_ で始まる行だけを渡す（他の変数を巻き込まない）
grep '^X_' .env | fly secrets import
```

本番の `source_account` にも手順 5.3 の行が要る。**ローカルと本番は別の DB**であり、
行が無いと取り込みは毎回スキップされる。

---

## 7. 運用中の確認

| 頻度 | 見るもの | 判断 |
| --- | --- | --- |
| 週次 | 管理画面の請求サイクル `fetched_resource_count` | 想定は 1 サイクル 300 前後。**桁違いなら第三者利用を疑う**（[security.md](security.md) T-01） |
| 週次 | コンソールの残高（請求書作成 → クレジット） | 想定の減り方（月 $1.5）と合っているか。`/2/usage/credits` は現状 404（付録 C.2） |
| 月次 | `GET /2/usage/tweets?usage.fields=daily_project_usage` | 日次の内訳。特定日に跳ねていないか |
| 随時 | `UNPARSED` の滞留件数 | 抽出精度の劣化を示す |

無償枠（`free_grants`）を使っている場合は **`expires_at` を過ぎると
残高が急に減る**。期限切れをコスト増と誤認しない。

---

## 8. 止めたいとき

| 手段 | 効果 | 副作用 |
| --- | --- | --- |
| **`X_INGESTION_ENABLED=false`** | スケジューラのビーン自体が作られない。**通常はこれ** | **反映にデプロイが要る**（[security.md](security.md)「未決定事項」） |
| X 側でトークンを無効化 | 認証エラーで失敗し続ける。**トークン漏洩時はこちら** | 再開には再発行が必要。失敗が記録に溜まり、連続 10 回で打ち切られる |
| `fly secrets unset X_BEARER_TOKEN` | 同上 | 同上 |
| `console.x.com` で支出上限を $0 にする | 課金が止まる | 本書「支出上限だけを信用しない」のとおり**効かない報告がある** |

取り込みだけが止まり、**公開カレンダーは動き続ける**（NFR-02）。

サイト全体の停止は別系統（`SITE_DISABLED`。[ADR-0013](adr/0013-site-kill-switch.md)）。

**自分で止めたのか、連続失敗で止まったのかを取り違えない。** 後者は本書「打ち切りから戻す」で戻す。

---

## 9. 打ち切りから戻す

**連続 10 回失敗すると取り込みが止まる**（FR-43 /
[x-integration.md](x-integration.md)「エラーハンドリング」）。自動では再開しない。

**原因を直さずに戻さない。** 失敗のたびに同じ範囲を取り直すため、
失敗が UTC の日跨ぎで続くと 24 時間の重複排除が切れて**毎日再課金される**
（最悪 $5/日）。止まっている状態そのものは課金を生まない。

### 9.1 なぜ手作業なのか

判定は「直近 10 件がすべて `FAILED`」（`IngestionHaltRule`）。
**打ち切られると新しい `ingestion_run` が作られない**ため、直近 10 件は
放っておいても永久に `FAILED` のままになる。時間で自然には戻らない。

管理画面に再開ボタンを置かないのも同じ理由で、押すだけで戻せると
原因が残ったまま再開してしまう（[api.md](api.md)「取り込み履歴」）。

### 9.2 手順

**1. 何が起きたかを見る。** `/admin/ingestion` に失敗の要約が並ぶ。
DB から直接見るなら:

```sql
SELECT id, started_at, status, error_summary
  FROM ingestion_run
 ORDER BY started_at DESC
 LIMIT 10;
```

**2. 原因を直す。**

| `error_summary` | 原因 | 対処 |
| --- | --- | --- |
| `HTTP 401` / `403` | Bearer Token の失効・権限変更 | 再発行して `fly secrets set X_BEARER_TOKEN=…`（本書「Bearer Token を取得して置く」） |
| `HTTP 404` | 情報源アカウントの削除・凍結・改名 | `source_account` を確認する。ハンドル変更なら `username` を直す |
| `HTTP 429` が続く | レート制限 | 時間を空ける。リトライ 3 回を使い切っている |
| 接続・読み取りのタイムアウト | 一時障害 | 再実行で直ることが多い |

**支出上限に当たっていないかも見る。** `console.x.com` の残高と
`GET /2/usage/tweets` の `project_usage` を確認する（本書「運用中の確認」）。

**3. 最新の失敗を 1 件だけ `CANCELLED` にする。**

```sql
UPDATE ingestion_run
   SET status = 'CANCELLED'
 WHERE id = (SELECT id FROM ingestion_run
              WHERE status = 'FAILED'
              ORDER BY started_at DESC
              LIMIT 1);
```

判定は「先頭から `FAILED` が連続する数」なので、`FAILED` 以外が 1 件入れば
連続が切れる。**次のスケジュール実行（最大 30 分後）から動き出す。**

**4. 戻ったことを確かめる。** `/admin/ingestion` の警告が消え、
その実行が「失敗（確認済み）」になっていること。次の実行が成功すること。

### 9.3 なぜ `CANCELLED` なのか

`SUCCESS` に書き換えると**走っていない実行を成功と記録する**ことになり、
FR-08 の「最後に取り込みが成功した日時」
（`max(finished_at) WHERE status = 'SUCCESS'`）が嘘になる。
`DELETE` すると失敗した事実が消える。

**ずれた記録は無い記録より悪い**（[CLAUDE.md](../CLAUDE.md)）。確認しなくなるため。
`CANCELLED` は失敗を記録に残したまま、判定からだけ外す。

- **10 件すべてを `CANCELLED` にしない。** 1 件で足りる。
  全部外すと、次に何回失敗したのかが分からなくなる
- **原因が直っていなければ 9 回失敗して再び止まる。** それが正しい挙動で、
  「戻したのに直っていなかった」ことに気づける

---

## 10. 漏洩したとき

手順は [security.md](security.md)「X API トークンの漏洩」に従う。要点だけ再掲する。

**履歴から消すだけで済ませない。必ず無効化して再発行する。**
一度でも公開された場所に出たトークンは、消しても漏れたままとして扱う。

---

## 11. 未決定事項

**番号で参照しない**（項目を消すと番号がずれる）。他の文書からは**項目名**で参照する。

1. **使用量エンドポイントの自動監視**。現状は手動確認。
   `fetched_resource_count` と X 側の実績を突き合わせる仕組みは未検討

次の 2 件は他の文書が正本。ここでは繰り返さない。

- **停止スイッチの反映にデプロイが要る** →
  [security.md](security.md)「未決定事項」「取り込みジョブの停止スイッチ」
- **説明のつかない実行が 1 件ある**（付録 C.4） →
  [x-integration.md](x-integration.md)「未決定事項」「取得件数と取り込み件数が食い違う実行」

---

## 付録 A. チェックリスト

- [ ] 情報源アカウントのハンドルが確定している（本書「着手前に決めておくこと」）
- [ ] クレジットを購入した
- [ ] **自動チャージを無効にした**（または追加額・閾値を本書「設定する値」の値にした）
- [ ] 請求サイクルの支出上限を設定した
- [ ] App の権限が **Read のみ**である
- [ ] Bearer Token をコマンドライン引数・チャット・スクリーンショットに出していない
- [ ] `.env` が `git status` に現れない
- [ ] `.env.example` に**キー名だけ**が入っている
- [ ] `GET /2/usage/tweets` が 200 を返す
- [ ] `source_account` に行があり、`last_fetched_tweet_id` が `NULL`
- [ ] `fly secrets list` に `X_BEARER_TOKEN` がある
- [ ] `fly.toml` とコミット履歴にトークンが入っていない
- [ ] Fly.io のインスタンスが 1 台に固定されている


## 付録 B. 出典

すべて 2026-09-02 に確認した。

| 内容 | 出典 |
| --- | --- |
| 従量課金の単価・月間 300 万リソース上限・24 時間 UTC の重複排除・自動チャージ・支出上限 | `docs.x.com/x-api/getting-started/pricing` |
| App-Only Bearer Token の取得（`POST /oauth2/token`） | `docs.x.com/fundamentals/authentication/oauth-2-0/bearer-tokens` |
| 使用量エンドポイント（`/2/usage/tweets`） | `docs.x.com/x-api/usage/introduction` |
| クレジット残高エンドポイント（`/2/usage/credits`） | `docs.x.com/x-api/usage/get-usage-credits`。**この環境では 404**。原因未特定（付録 C.2） |
| `tweet.fields` が現行のパラメータ名／`note_tweet` の意味 | `docs.x.com/x-api/fundamentals/data-dictionary` |
| `GET /2/users/{id}/tweets` のパラメータと `max_results` の範囲 | `docs.x.com/x-api/posts/user-posts-timeline-by-user-id` |
| アクセス取得の手順（`console.x.com`、認証情報の 1 回限りの表示） | `docs.x.com/x-api/getting-started/getting-access` |
| **稼働中の API の仕様そのもの**（パス / パラメータ / 認証方式） | `GET https://api.x.com/2/openapi.json`（無料）。文書と実挙動が食い違ったときの一次情報 |
| 支出上限が効かなかった事例 | `devcommunity.x.com`「Billing cycle Spend Cap and negative credit balance not enforced」 |
| 自動チャージの既定値と閾値の発火条件 | `devcommunity.x.com`「Update to Auto-Recharge Behavior for Free Credits」ほか二次情報 |

---

---

## 付録 C. 実施記録

| 日付 | 手順 | 結果 |
| --- | --- | --- |
| 2026-09-02 | 手順 2 開発者アカウントと App | **完了。** Developer Console にアクセスでき、App `hatena-calendar` を `Default Project` 配下に作成した（`Pay Per Use` / `active`） |
| 2026-09-02 | 手順 2 App の認証設定 | **完了。** User authentication settings は未設定（「セットアップ」ボタンのまま）。権限は「読む — 投稿とプロフィール情報を読む」のみ |
| 2026-09-02 | 手順 3 課金ガードレール | **完了。** 下表のとおり、推奨値どおりに設定した |
| 2026-09-02 | 手順 4 Bearer Token | **完了。** App 作成時に控えたトークンを `.env` に格納した（116 文字 / `AAAAAAAAAA` 始まり） |
| 2026-09-02 | 手順 5.1 トークンの有効性 | **完了。** `GET /2/usage/tweets` が `200`。課金なし |
| 2026-09-02 | 手順 5.2 ユーザー ID の解決 | **完了。$0.010 を消費。** 下の C.3 に結果を記録した |
| 2026-09-02 | 手順 5.3 `source_account` の投入 | **完了。** ローカル DB に 1 行。`last_fetched_tweet_id` は `NULL` |
| 2026-09-02 | 手順 5.4 投稿の取得 | **完了。$0.025 を消費。** 5 件取得。`note_tweet` を確認 |
| 2026-09-02 | 手順 5.5 課金の照合 | **完了。** `project_usage` が 0 → 5。返却件数と一致 |
| 2026-09-02 | **初回の実取り込み** | **完了。$0.005 を消費。** 結果は C.4 |
| 2026-09-02 | **通しの動作確認** | **完了。課金なし**（同日の再取得は重複排除が効く）。結果は C.5 |
| 2026-09-03 | **手順 9 打ち切りからの復帰** | **完了。課金なし。** ローカル DB に `FAILED` を 10 件入れて `halted: true` を確認し、本書「手順」の `UPDATE` で最新 1 件を `CANCELLED` にしたところ `halted: false` に戻った（`consecutiveFailureCount` は 10 → 0） |
| — | 手順 6 Fly.io | 未着手。デプロイ時に行う |

**手順 5 までの実費は $0.035**（User Read $0.010 + Post Read 5 件 $0.025）。
手順書の事前見積もりと一致した。初回の実取り込みを含めた累計は **$0.040**。
手順 3 の設定値（`console.x.com` → 請求書作成 → クレジット）:

| 設定 | 値 | 推奨値との一致 |
| --- | --- | --- |
| 残高 | $10.00 | 一致 |
| 無料クレジット | $0.00 | バウチャー無し。異常ではない（本書「コンソールで紛らわしい点」） |
| 自動チャージ | **オフ** | 一致 |
| 請求サイクル上限 | $5.00 | 一致 |
| 請求サイクル | 2026-09-02 〜 2026-10-02 | 購入日起点。暦月ではない |

**この時点での最大損失は $10（残高）、1 サイクルあたり $5 に限定されている。**

### C.1 提出したユースケース説明


申請時に「X のデータおよび API のすべてのユースケースを説明してください」欄へ
提出した原文。**差し戻しや再申請の際は、この文面を起点にする。**

```text
I am building a non-commercial, personal fan project: a public web
calendar showing the live-performance schedule of XINXIN, a Japanese
independent ("underground") idol group.

How I use the API
- Read-only, app-only (OAuth2 Bearer) access to GET /2/users/:id/tweets
  for a single account: the group's official account.
- I fetch only new Posts using since_id, poll about every 30 minutes,
  and set exclude=replies,retweets. The account posts roughly 10 times
  per day, so my request volume is very low.
- I take no write actions of any kind. I do not post, reply, like,
  follow, or send messages through the API.

What I do with the data
- From each announcement Post I extract factual event details only:
  date, venue, event name, and performance start/end times. These are
  stored as normalized structured fields in my own database.
- I do not store or republish the full text of Posts, and I do not
  store or display images or any other media from X.
- Every event entry on my site links back to the original Post on
  x.com as its source, so visitors can verify the information at
  its origin.

Display and audience
- The calendar is a free public website with no user registration and
  no advertising. Only I, the operator, can sign in, and only to
  correct mistakes made by the automatic extraction.
- The site states clearly on every page that it is an unofficial,
  fan-made tool with no affiliation with the group or its management,
  and it provides a contact method for removal requests.

I will not resell, redistribute, or provide bulk or derivative access
to any data obtained from the X API, and I will not use it to train
machine-learning models.
```

**この文面は実装に対する約束である。** 次を変えるときは、
申請内容との食い違いが生じていないか確認する。

| 文面での約束 | 対応する設計 |
| --- | --- |
| 単一アカウントのみ / 読み取り専用 | [requirements.md](requirements.md) FR-40、本書 手順 2 |
| `since_id` による差分取得・30 分間隔・`exclude=replies,retweets` | [x-integration.md](x-integration.md)「投稿の取得」 |
| 投稿本文を保存・再掲しない / 画像を保持しない | [CLAUDE.md](../CLAUDE.md) 法務方針、LR-03 |
| 各出演情報に出典 URL を添える | [data-model.md](data-model.md) `source_url` |
| 非公式である旨を全ページに明示 / 削除要請の連絡手段 | LR-01 / LR-05 |
| 再販・再配布・機械学習への利用をしない | 公開 API は読み取り専用（[api.md](api.md)） |

### C.2 手順 5.1 で分かったこと


実測のレスポンス（`GET https://api.x.com/2/usage/tweets`、HTTP 200）:

```json
{"data":{"cap_reset_day":2,"project_cap":"3000000",
         "project_id":"...","project_usage":"0"}}
```

`api-version: 2.168` / `x-rate-limit-limit: 50`。

**`project_cap` が `3000000` であることが実測で確定した。**
二次情報の「200 万」は誤りで、公式文書の 300 万が正しい（本書「裏取りの時点と、古い情報の見分け方」）。

#### 日次内訳が返らなかったのは呼び方の誤り

当初「文書にある `daily_project_usage` が返らない」と記録したが、**これは誤りだった。**
`usage.fields` を指定していなかっただけで、指定すれば返る。

```
?days=7&usage.fields=daily_project_usage
→ {"daily_project_usage":{"project_id":"...",
     "usage":[{"date":"2026-09-02T00:00:00.000Z","usage":"5"}]}}
```

`daily_client_app_usage` を指定すると App 単位の内訳も返る。
**文書は正しく、検証が不十分だった。**

#### `/2/usage/credits` の 404 は原因未特定

こちらは呼び方を変えても解消しなかった。
末尾スラッシュ・単数形・親パス、`api.x.com` と `api.twitter.com` の
いずれでも **404（本文なし）**。

ただし**「文書が間違っている」とは言えない。**
稼働中の API 自身が返す OpenAPI 仕様（`GET /2/openapi.json`、v2.168）に
このエンドポイントは**存在している**。

```
/2/usage/credits  operationId: getUsageCredits
                  security: [OAuth2UserToken, BearerToken]
                  parameters: なし
```

**App-Only Bearer が許可されているにもかかわらず 404 が返る。**

**当方の環境の問題ではないことは確認済み。**

- 別の開発者が 2026-08-27 に同じ症状を公開報告している
  （`GET /2/usage/credits` が `content-length: 0` の 404。
  ヘッダの構成も当方の観測と一致する）
- **`docs.x.com` の API Playground から実行しても 404。**
  別アプリの新規トークンでも再現するため、当方のアプリ設定にも依存しない

**このエンドポイントは現時点で機能していない**と判断してよい。
原因（未展開 / 実装未追従 / 経路側の不具合）は依然として未特定。

**結論**: 残高は API から取らず、コンソールで見る。
`docs.x.com` の記述を疑う根拠は無い。**将来動くようになる可能性はある**ため、
手順 7 の監視を自動化するときに再確認する。

### C.3 情報源アカウント


`GET /2/users/by/username/xinxin_official` の結果（HTTP 200、$0.010）:

| 項目 | 値 |
| --- | --- |
| `username` | `xinxin_official` |
| `name` | `XINXIN【公式】` |
| `id` | `1907616831361396737` |

**この呼び出しは二度と行わない**（FR-40 /
[x-integration.md](x-integration.md)「ユーザー ID の解決（初回のみ）」）。ID は
`source_account.x_user_id` に永続化し、以降はそこから読む。
再取得すればそのたびに $0.010 が課金される。

`BIGINT` の上限（約 9.22 × 10^18）に対して 1.91 × 10^18 なので、
[data-model.md](data-model.md) の型定義で収まっている。

### C.4 初回の実取り込み


2026-09-02、ローカルで `bootRun` し、スケジューラ経由で 1 回実行した。

**実行前に取得範囲を固定した。** `last_fetched_tweet_id` がテストの残骸で
`2002` になっており、そのまま動かすと**最新 1000 件を取得して $5.00**、
支出上限ちょうどになるところだった。疎通確認で見た最古の投稿
（`2094065845186318628`、2026-08-30）に固定してから実行した。

| | |
| --- | --- |
| 取得 | 5 件（1 ページ。`next_token` なし） |
| 登録 | 1 件 |
| 未処理 | 4 件 |
| 実費 | **$0.005**（5 件中 4 件は同日に取得済みで重複排除が効いた） |

**4 件が未処理になったのは当時の設計どおり。** 内訳は「出演時刻が未確定の情報解禁」が
3 件と、お礼投稿が 1 件。前者はその後 ADR-0021 で対象に入れたため、
いま同じ投稿を取り込めば 3 件は時刻なしで登録される
（[x-integration.md](x-integration.md)「抽出対象の判定」経路 B）。
**この計測をやり直す必要はない。** 記録しているのは課金額であり、
未処理の内訳が変わっても実費 $0.005 は変わらない。

**24 時間の重複排除を実測で確認した。** 同じ 5 件を取り直しても
`project_usage` は増えなかった。

#### 見つかった不具合

**チケット URL が `NULL` になっていた。** 投稿が `🔗 https://...` と
スペースを挟んでおり、正規表現が一致していなかった。
サンプル 13 件には無い表記で、実際に動かすまで見つからなかった
（[x-integration.md](x-integration.md)「実 API で見つかった表記ゆれ」）。

#### 説明のつかない実行が 1 件あった

最初のスケジュール実行が **`fetched_resource_count = 2` を記録しながら、
`ingested_post` に 1 行も入れていなかった**。実装上、取得した投稿は
取り込み済みでない限り必ず記録されるため、この組み合わせは起こらないはずである。

**原因は特定できていない。** 調査の途中で該当の `ingestion_run` を消してしまい、
何を取得したのかを追えなくなった。

その後の実行はすべて整合している（5 件取得 → 5 行挿入 → 1 件登録）。
**取得件数の記録そのものが正しいことは、X 側の返却件数と突き合わせて確認済み。**
過大に数えるぶんには課金の見積もりが安全側に倒れるが、過小だと気づけない。

**次に同じ数字が出たら、記録を消さずに調べる。**
本書「未決定事項」の未決定事項に残した。

#### テストは開発 DB を触らない

かつては結合テストが `compose.yaml` の DB を共有し、`source_account` を含めて
各テーブルを削除していた。**この事故が実際に起きている**（上の「実行前に取得範囲を
固定した」がそれ）。

現在は Testcontainers が実行ごとに使い捨ての PostgreSQL を立てる
（[CLAUDE.md](../CLAUDE.md)）。`./gradlew test` を何度走らせても開発 DB は変わらない。
`docker compose down` した状態でもテストは通る。

**それでも取り込みを動かす前に `last_fetched_tweet_id` は確認する。**
値が古いほど取得範囲が広がり、そのまま課金になる。

```bash
docker compose exec -T db psql -U hatenacal -d hatenacal \
  -c "SELECT username, last_fetched_tweet_id FROM source_account;"
```

#### 公開画面への反映は最大 5 分遅れる

取り込みが終わっても、公開カレンダーはすぐには変わらない。
ISR で 300 秒キャッシュしているため（[architecture.md](architecture.md)「キャッシュ戦略」）。
**不具合ではない。**

開発中に古い表示が残って紛らわしいときは、`.next` を消してから
`npm run dev` をやり直す。`.next/cache` だけでは消えないことがある。

### C.5 通しの動作確認

2026-09-02、ローカルで backend と frontend を起動し、**X の投稿から公開画面まで**
実データで通した。

| 確認項目 | 結果 |
| --- | --- |
| 定期取り込み（30 分間隔） | 自動実行。5 件取得 / 1 件登録 / 4 件未処理 |
| 公開カレンダー | `2026/09/05 『new story』` を会場・出演時刻・物販時刻・チケット・出典つきで表示 |
| 管理 API 出演情報一覧（FR-24） | 1 件、`sourceType: AUTO` |
| 管理 API 未処理投稿（FR-25） | 4 件。投稿 URL は `source_account` のハンドルから生成 |
| 未認証での `/admin/*` | `307` で `/admin/login` へ |
| 誤ったパスワードでの認証 | `{"authenticated":false}` + **HTTP 200**（総当たり対策どおり。[api.md](api.md)「管理者パスワードの検証」） |
| 管理画面へのログイン | ブラウザで確認済み。**`$2b$` のハッシュで通る** |

**この日の再取得はすべて課金ゼロ**だった（同じ UTC 日の重複排除）。
累計は初回取り込みまでの **$0.040** のまま。
