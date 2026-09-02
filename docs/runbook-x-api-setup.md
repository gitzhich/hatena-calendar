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

| # | 決めること | 参照 | 状態 |
| --- | --- | --- | --- |
| 1 | 情報源アカウントの X ハンドル | [requirements.md](requirements.md) 未決定事項 5 | **未決定** |
| 2 | 月あたりの支出上限（ガードレールに設定する値） | 本書 手順 3 | 本書で $5 を提案 |
| 3 | 課金に使う支払い方法 | — | 要準備 |

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

`console.x.com` → Billing / Credits で設定する。

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

### 3.3 支出上限だけを信用しない

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

ローカルはリポジトリ直下の `.env`（`.gitignore` 済み）に置く。
エディタで直接書き込むか、履歴に残らない形で追記する。

```bash
# 履歴にトークンを残さずに追記する
read -rs -p 'X_BEARER_TOKEN: ' TOKEN && printf 'X_BEARER_TOKEN=%s\n' "$TOKEN" >> .env && unset TOKEN
```

`.env.example` には**キー名だけ**を書く（[architecture.md](architecture.md) 第 7 章）。
値は絶対に入れない。本アプリが必要とするのは次の 2 つ。

```
X_BEARER_TOKEN=
X_SOURCE_USERNAME=
```

### 4.3 置いた直後に確認する

```bash
git status --porcelain          # .env が現れないこと
git check-ignore -v .env        # .gitignore に当たっていること
grep -n '^X_' .env.example      # キー名だけで、= の右に値が無いこと
```

`.env.example` に上の 2 キーが無ければ追記する（**値は空のまま**）。

---

## 5. 疎通を確認する

**ここから課金が発生する。** 各段階の費用を明記する。単価は
[x-integration.md](x-integration.md) 第 4.1 節。

以降 `$X_BEARER_TOKEN` は `.env` から読み込んだ値とする
（`set -a; . ./.env; set +a`）。

### 5.1 トークンの有効性を確認する（$0）

**Post を 1 件も読まないので課金されない。** 最初にこれを叩く。

```bash
curl -s -H "Authorization: Bearer $X_BEARER_TOKEN" \
     'https://api.x.com/2/usage/credits'
```

```json
{ "data": { "total_balance": 10.00, "prepaid_balance": 10.00,
            "free_balance": 0.00, "free_grants": [] } }
```

- `401` が返る → トークンが誤っているか無効化されている
- `total_balance` が 0 → クレジットが未購入。手順 3 に戻る
- `free_grants` に無償枠がある場合、**有効期限がある**。`expires_at` を控えておく

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
VALUES ('<ハンドル。@ は含めない>', <上で得た数値 ID>);
```

`last_fetched_tweet_id` は **`NULL` のままにする**。
初回は未設定として扱われ、`start_time` で範囲を限定したバックフィルになる
（[x-integration.md](x-integration.md) 第 8 章）。

ローカルの実行例:

```bash
docker compose up -d --wait
docker compose exec -T db psql -U hatenacal -d hatenacal -c "INSERT INTO ..."
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

`daily_project_usage[].tweets_consumed` が、手順 5.4 で返った件数と一致すること。
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
| 週次 | `GET /2/usage/credits` の `total_balance` | 想定の減り方（月 $1.5）と合っているか |
| 月次 | `GET /2/usage/tweets` の日次内訳 | 特定日に跳ねていないか |
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
| `console.x.com` で支出上限を $0 にする | 課金が止まる | 第 3.3 節のとおり**効かない報告がある** |

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
- [ ] `GET /2/usage/credits` が 200 を返す
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
| クレジット残高エンドポイント（`/2/usage/credits`、`free_grants`） | `docs.x.com/x-api/usage/get-usage-credits` |
| `tweet.fields` が現行のパラメータ名／`note_tweet` の意味 | `docs.x.com/x-api/fundamentals/data-dictionary` |
| `GET /2/users/{id}/tweets` のパラメータと `max_results` の範囲 | `docs.x.com/x-api/posts/user-posts-timeline-by-user-id` |
| アクセス取得の手順（`console.x.com`、認証情報の 1 回限りの表示） | `docs.x.com/x-api/getting-started/getting-access` |
| 支出上限が効かなかった事例 | `devcommunity.x.com`「Billing cycle Spend Cap and negative credit balance not enforced」 |
| 自動チャージの既定値と閾値の発火条件 | `devcommunity.x.com`「Update to Auto-Recharge Behavior for Free Credits」ほか二次情報 |

---
