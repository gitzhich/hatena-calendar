# 手順書 — X API の実キー設定

最終更新: 2026-09-02

関連文書: [CLAUDE.md](../CLAUDE.md) / [x-integration.md](x-integration.md) /
[architecture.md](architecture.md) 第 7 章 / [security.md](security.md) 第 6.1 節

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

### 0.3 未実装のため手順で代替している箇所

実装が追いつくまで、以下は手作業で行う。実装が入ったら本書を更新する。

| 箇所 | 現状 | 代替 |
| --- | --- | --- |
| `XApiClient` | 未実装 | 手順 4・5 は `curl` で叩く |
| ユーザー ID の解決（[x-integration.md](x-integration.md) 第 3.1 節） | 未実装 | 手順 5 で 1 回だけ手で叩き、結果を DB に入れる |
| `source_account` への投入 | 投入手段なし | 手順 5 で SQL を直接実行する |
| 取り込みの停止スイッチ | 未実装（[security.md](security.md) 第 9 章） | 手順 8 の暫定手段を使う |

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
このプロジェクトの想定コストは **月 $1.5 程度**（[x-integration.md](x-integration.md) 第 4.3 節）で、
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
| （有効にする場合）発火閾値 | $2 | 購入額より十分低くする（第 3.1 節の仕様） |
| 請求サイクルの支出上限 | **$5** | 想定の 3 倍強。超えたら実装かトークンが壊れている |

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
  暦月ではないため、支出上限のリセット日を月初と思い込まない

### 3.4 支出上限だけを信用しない

開発者フォーラムには、**支出上限と残高マイナスが 1 週間以上にわたって
効かず、リクエストが通り続けた**という報告がある。
上限設定は「効いたら儲けもの」の位置づけで扱い、**検知を自前で持つ**。

| 層 | 手段 | 状態 |
| --- | --- | --- |
| X 側 | 請求サイクルの支出上限 | 手順 3.2 で設定。**単独では信用しない** |
| X 側 | 自動チャージを無効化 | 手順 3.2。残高が尽きれば止まる |
| アプリ側 | `ingestion_run.fetched_resource_count` の当月合計を管理画面に出す | [security.md](security.md) T-01 |
| アプリ側 | 連続失敗で取り込みを打ち切る | [x-integration.md](x-integration.md) 第 7 章 |
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
**値を伏せた状態で `.env.example` に登録済み**（[architecture.md](architecture.md) 第 7 章）。
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

### 4.2.1 `.env` を自動で読むものは無い

**Spring Boot も Next.js も、リポジトリ直下の `.env` を読まない。**

- Spring Boot が見るのは**プロセスの環境変数**（`application.yml` の `${X_BEARER_TOKEN}`）
- Next.js が読むのは `frontend/.env.local` などで、リポジトリ直下ではない

`.env` は**シェルで読み込むための置き場**である。読み込むときは
**`X_` だけに絞る**（理由は第 5 章冒頭）。

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
[x-integration.md](x-integration.md) 第 4.1 節。

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
残高はコンソールの「請求書作成 → クレジット」で見る（第 3.3 節）。

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
[x-integration.md](x-integration.md) 第 3.1 節）。

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
（[x-integration.md](x-integration.md) 第 8 章）。

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
  （[x-integration.md](x-integration.md) 第 3.3 節）
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

`fly.toml` に書かない。**Secrets に入れる**（[security.md](security.md) T-01）。

`fly secrets set KEY=VALUE` は値がシェル履歴に残る。標準入力から流し込む。

```bash
# .env から X_ で始まる行だけを渡す（他の変数を巻き込まない）
grep '^X_' .env | fly secrets import
```

`DATABASE_URL` などが未設定なら合わせて投入する
（一覧は [architecture.md](architecture.md) 第 7 章）。

```bash
fly secrets list     # 名前とダイジェストだけが出る。値は表示されない
```

**インスタンスは 1 台に固定する。** 複数台だと `@Scheduled` が多重起動し、
同じ範囲を並行して取得して課金が倍になる（[CLAUDE.md](../CLAUDE.md)）。

本番の `source_account` にも手順 5.3 の行が要る。**ローカルと本番は別の DB**である。

---

## 7. 運用中の確認

| 頻度 | 見るもの | 判断 |
| --- | --- | --- |
| 週次 | 管理画面の当月 `fetched_resource_count` | 想定は月 300 前後。**桁違いなら第三者利用を疑う**（[security.md](security.md) T-01） |
| 週次 | コンソールの残高（請求書作成 → クレジット） | 想定の減り方（月 $1.5）と合っているか。`/2/usage/credits` は現状 404（付録 C.2） |
| 月次 | `GET /2/usage/tweets?usage.fields=daily_project_usage` | 日次の内訳。特定日に跳ねていないか |
| 随時 | `UNPARSED` の滞留件数 | 抽出精度の劣化を示す |

無償枠（`free_grants`）を使っている場合は **`expires_at` を過ぎると
残高が急に減る**。期限切れをコスト増と誤認しない。

---

## 8. 止めたいとき

取り込みの停止スイッチは**未実装**（[security.md](security.md) 第 9 章）。
それまでの暫定手段:

| 手段 | 効果 | 副作用 |
| --- | --- | --- |
| `fly secrets unset X_BEARER_TOKEN` | 取り込みが認証エラーで失敗し続ける | 失敗が記録に溜まる。連続失敗の打ち切りで止まる |
| X 側でトークンを無効化 | 同上。**トークン漏洩時はこちら** | 再開には再発行が必要 |
| `console.x.com` で支出上限を $0 にする | 課金が止まる | 第 3.4 節のとおり**効かない報告がある** |

サイト全体の停止は別系統（`SITE_DISABLED`。[ADR-0013](adr/0013-site-kill-switch.md)）。

---

## 9. 漏洩したとき

手順は [security.md](security.md) 第 6.1 節に従う。要点だけ再掲する。

**履歴から消すだけで済ませない。必ず無効化して再発行する。**
一度でも公開された場所に出たトークンは、消しても漏れたままとして扱う。

---

## 10. 未決定事項

1. **支出上限の値**。本書は $5 を提案しているが、バックフィルを実行する月は
   一時的に超える可能性がある（[x-integration.md](x-integration.md) 第 8 章）
2. **取り込みの停止スイッチ**（[security.md](security.md) 第 9 章）。
   実装したら手順 8 を差し替える
3. **使用量エンドポイントの自動監視**。現状は手動確認。
   `fetched_resource_count` と X 側の実績を突き合わせる仕組みは未検討

---

## 付録 A. チェックリスト

- [ ] 情報源アカウントのハンドルが確定している（第 0.2 節）
- [ ] クレジットを購入した
- [ ] **自動チャージを無効にした**（または追加額・閾値を第 3.2 節の値にした）
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
| — | 手順 6 Fly.io | 未着手。デプロイ時に行う |

**ここまでの実費は $0.035**（User Read $0.010 + Post Read 5 件 $0.025）。
手順書の事前見積もりと一致した。
手順 3 の設定値（`console.x.com` → 請求書作成 → クレジット）:

| 設定 | 値 | 推奨値との一致 |
| --- | --- | --- |
| 残高 | $10.00 | 一致 |
| 無料クレジット | $0.00 | バウチャー無し。異常ではない（第 3.3 節） |
| 自動チャージ | **オフ** | 一致 |
| 請求サイクル上限 | $5.00 | 一致 |
| 請求サイクル | 2026-09-02 〜 2026-10-02 | 購入日起点。暦月ではない |

**この時点での最大損失は $10（残高）、1 サイクルあたり $5 に限定されている。**

### C.2 手順 5.1 で分かったこと

実測のレスポンス（`GET https://api.x.com/2/usage/tweets`、HTTP 200）:

```json
{"data":{"cap_reset_day":2,"project_cap":"3000000",
         "project_id":"...","project_usage":"0"}}
```

`api-version: 2.168` / `x-rate-limit-limit: 50`。

**`project_cap` が `3000000` であることが実測で確定した。**
二次情報の「200 万」は誤りで、公式文書の 300 万が正しい（第 0.1 節）。

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
考えられる原因（いずれも未確認）:

- 当アカウント / 当プランにまだ展開されていない
- 仕様には載っているが実装が追いついていない
- ルーティング側の不具合

X の通常のエラーは JSON のエラーエンベロープを返すのに対し、
**これは本文が空の 404** である。アプリケーション層ではなく
経路側で落ちている可能性を示すが、これも推測の域を出ない。

**結論**: 残高は API から取らず、コンソールで見る。
`docs.x.com` の記述を疑う根拠は無い。

### C.3 情報源アカウント

`GET /2/users/by/username/xinxin_official` の結果（HTTP 200、$0.010）:

| 項目 | 値 |
| --- | --- |
| `username` | `xinxin_official` |
| `name` | `XINXIN【公式】` |
| `id` | `1907616831361396737` |

**この呼び出しは二度と行わない**（FR-40 /
[x-integration.md](x-integration.md) 第 3.1 節）。ID は
`source_account.x_user_id` に永続化し、以降はそこから読む。
再取得すればそのたびに $0.010 が課金される。

`BIGINT` の上限（約 9.22 × 10^18）に対して 1.91 × 10^18 なので、
[data-model.md](data-model.md) の型定義で収まっている。

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
| `since_id` による差分取得・30 分間隔・`exclude=replies,retweets` | [x-integration.md](x-integration.md) 第 3.2 節 |
| 投稿本文を保存・再掲しない / 画像を保持しない | [CLAUDE.md](../CLAUDE.md) 法務方針、LR-03 |
| 各出演情報に出典 URL を添える | [data-model.md](data-model.md) `source_url` |
| 非公式である旨を全ページに明示 / 削除要請の連絡手段 | LR-01 / LR-05 |
| 再販・再配布・機械学習への利用をしない | 公開 API は読み取り専用（[api.md](api.md)） |
