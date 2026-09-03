# API 設計 — XINXIN 出演情報カレンダー

最終更新: 2026-09-03

関連文書: [CLAUDE.md](../CLAUDE.md) / [docs/requirements.md](requirements.md) /
[docs/data-model.md](data-model.md) / [docs/architecture.md](architecture.md)

---

## 1. 前提

Spring Boot が提供する REST API の契約。呼び出すのは **Next.js（BFF）のサーバ側だけ**で、
ブラウザから直接叩かれることはない（[architecture.md](architecture.md) 第 3 章）。

API は 3 系統に分かれる。これに、認証を掛けない監視用エンドポイントが 1 つ加わる。

| 系統 | パス | 認証 | 用途 |
| --- | --- | --- | --- |
| 公開 API | `/api/public/**` | 公開キー | 閲覧者向け。**`GET` のみ** |
| 管理 API | `/api/admin/**` | 管理キー | 登録・編集・削除・点検 |
| 内部 API | `/internal/**` | 管理キー | 管理者パスワードの検証 |
| 監視 | `/actuator/health` | **なし** | Fly.io のヘルスチェック（第 4.3 節） |

**これ以外のパスはすべて拒否する**（`anyRequest().denyAll()`）。
許可を明示的に列挙する形にしてあり、新しいエンドポイントは
`SecurityConfig` に足さない限り 403 になる（第 2.2 節）。

**将来 EAS から叩くのは公開 API だけ**であり、その前提で設計する（NFR-07）。

---

## 2. 認証

### 2.1 2 種類のキーに分ける

Next.js から Spring Boot への呼び出しは、共有シークレットをヘッダで送る。
**このキーを 2 種類に分ける。**

| ヘッダ | 環境変数 | 通せる範囲 |
| --- | --- | --- |
| `X-Api-Key` | `INTERNAL_API_KEY` | 公開 API のみ |
| `X-Admin-Api-Key` | `INTERNAL_ADMIN_API_KEY` | 管理 API・内部 API・**公開 API** |

**管理キーは公開 API も通せる（上位互換）。公開キーで管理 API は通らない。**
管理画面が公開 API を呼ぶ場面でキーを持ち替えずに済ませるための設計で、
分離の目的（露出の広い公開キーに管理権限を与えない）は逆向きの禁止だけで達成される。

キーを 1 種類にすると、**公開ページの取得に使うキーが漏れただけで管理操作まで通ってしまう**。
公開データの取得は全ページのレンダリングで使われ露出機会が多いため、分離して被害を限定する。

Next.js は**管理者セッション Cookie の検証に成功した場合にのみ** `X-Admin-Api-Key` を使う。
公開ページのレンダリングでは管理キーを読み込まない。

### 2.2 認可の実装方針

Spring Security で**デフォルト拒否**にし、パスごとに必要なキーを明示的に許可する（NFR-03）。

- キーの比較は**固定時間比較**で行う（文字列の `equals` を使わない）
- キーが一致しない場合は `403` を返し、**理由を区別できるメッセージを返さない**
- 管理者パスワードの検証は `/internal/auth` でのみ行う（第 6 章）

---

## 3. 共通仕様

### 3.1 形式

| 項目 | 規約 |
| --- | --- |
| Content-Type | `application/json; charset=UTF-8` |
| JSON のキー | camelCase |
| 文字コード | UTF-8 |
| 値がない項目 | **キーを含めて `null` を返す**（キー自体を省略しない） |

### 3.2 日付と時刻

**ここを誤ると日付がずれる。** [data-model.md](data-model.md) 第 6 章の方針に対応する。

| 種類 | 形式 | 例 | 意味 |
| --- | --- | --- | --- |
| イベントの開催日 | `YYYY-MM-DD` | `"2026-09-15"` | **JST の暦日**。オフセットを付けない |
| 出演時刻・物販時刻 | `HH:mm:ss` | `"19:50:00"` | **JST のローカル時刻**。オフセットを付けない |
| システム日時 | ISO 8601（UTC） | `"2026-08-28T01:00:00Z"` | 作成日時・取り込み日時など |

イベントの開催日・出演時刻・物販時刻に**タイムゾーン情報を付けない**。
これらは特定の瞬間ではなく暦日・ローカル時刻であり、
オフセットを付けるとクライアント側の変換で日付がずれる。

### 3.3 エラー

RFC 7807（Problem Details）形式で返す。

```json
{
  "type": "about:blank",
  "title": "Validation Failed",
  "status": 400,
  "detail": "eventName は必須です",
  "instance": "/api/admin/appearances"
}
```

**スタックトレース、SQL、内部のクラス名を含めない**（NFR-03）。

| ステータス | 発生条件 |
| --- | --- |
| `400` | バリデーション違反、クエリパラメータの形式不正 |
| `403` | API キーが不正、または必要な系統のキーでない |
| `404` | 指定した ID のリソースが存在しない |
| `409` | 一意制約違反（同じ日付・同じイベント・同じ開始時刻が既に存在） |
| `429` | レート制限（NFR-03） |
| `500` | サーバ内部エラー。詳細はログにのみ残す |

---

## 4. 公開 API

すべて `GET`。更新系のメソッドを**定義しない**（NFR-03）。

### 4.1 期間内の出演情報一覧

```
GET /api/public/appearances?from=2026-09-01&to=2026-09-30
```

カレンダー 1 か月分を **1 リクエストで**取得する（NFR-01）。
日付ごとに分割して呼ばない。

| パラメータ | 必須 | 説明 |
| --- | --- | --- |
| `from` | ○ | 取得開始日（JST の暦日、含む） |
| `to` | ○ | 取得終了日（JST の暦日、含む） |

- `from` > `to` の場合は `400`
- 期間の上限は **62 日**とする。超えたら `400`（月表示 2 か月分で足りる）
- **FR-05 が定める表示範囲を外れる場合は `400`。**
  Next.js 側で弾く前提だが、公開 API 単体でも境界を守る。
  期間の上限は 1 リクエストの幅を縛るだけで、
  **リクエストできる月の種類数を縛らない**ため、この検証が別に要る
  （[security.md](security.md) T-04）

**レスポンス**

```json
{
  "appearances": [
    {
      "id": 12,
      "appearanceDate": "2026-09-16",
      "eventName": "lonlium pre.『LONELY KIDS』",
      "venueName": "愛知・大須RADHALL",
      "performanceStartTime": "19:50:00",
      "performanceEndTime": "20:15:00",
      "merchStartTime": "21:25:00",
      "merchEndTime": "22:35:00",
      "ticketUrl": "https://livepocket.jp/e/lk-nagoya0916",
      "sourceUrl": "https://x.com/.../status/..."
    },
    {
      "id": 8,
      "appearanceDate": "2026-09-15",
      "eventName": "#ﾆｷﾌﾟﾚ『カンシャサイ。-秋-』",
      "venueName": "東京・渋谷音楽堂/Shibuya Milkyway/...",
      "performanceStartTime": null,
      "performanceEndTime": null,
      "merchStartTime": null,
      "merchEndTime": null,
      "ticketUrl": "https://t-dv.com/20260915_nikipre",
      "sourceUrl": "https://x.com/.../status/..."
    }
  ]
}
```

- **`eventName` は告知の原文**。照合用の `eventKey` は**返さない**
  （内部の実装詳細であり、画面に出す値ではない。[data-model.md](data-model.md) 第 4.3.2 節）
- `sourceType`、`createdAt`、`ingestedPostId` も公開 API では返さない。
  閲覧者に不要な内部情報を出さない
- 並び順は `appearanceDate` 昇順、次に `performanceStartTime` 昇順。
  **時刻が `null` のものは同じ日付の末尾**に置く（FR-03）
- **同じ日に同じ `eventName` が複数並ぶことがある。** 1 つのイベントの中で
  複数回出演する告知があるため（[x-integration.md](x-integration.md) 第 5.10 節）。
  会場と時刻で区別できる
- 該当がない場合は `appearances` が空配列。`404` にしない

### 4.2 データの状態

```
GET /api/public/status
```

FR-08 のために最終更新日時を返す。

```json
{
  "lastSuccessfulIngestionAt": "2026-08-28T01:00:00Z",
  "stale": false
}
```

| フィールド | 説明 |
| --- | --- |
| `lastSuccessfulIngestionAt` | 最後に取り込みが成功した日時（UTC）。一度も成功していなければ `null` |
| `stale` | 最終成功から **24 時間**以上経過していれば `true`（FR-08）。境界は 24 時間ちょうどを含む |

`stale` の判定をサーバ側で行うのは、閲覧者に見せる基準を 1 か所に集約するため。

**一度も成功していなければ `stale` は `false`。** FR-08 の警告は最終更新日時に
併記するものであり、併記する日時が無い場面で警告だけを出しても閲覧者は行動を決められない。
加えて、手動登録だけで運用している間（取り込みを止めているのが正常な状態）
ずっと警告が出続けることになり、警告そのものが読み飛ばされる。
取り込みが動いていないことの検知は運用側の責務とする（NFR-04 / FR-24）。

**取り込みの内部情報はこの API に載せない。** 失敗理由・取得リソース数・実行中かどうかは
運用の情報であり、公開 API から読めるようにしない（NFR-03）。

### 4.3 ヘルスチェック

```
GET /actuator/health
```

```json
{ "status": "UP" }
```

**この 1 つだけ認証を掛けない。** Fly.io のヘルスチェック
（`fly.toml` の `http_service.checks`）が内部 API キーを送れないためで、
認証を要求するとインスタンスが常に不健全と判定されて再起動を繰り返す。

**返すのは `status` だけ。** `management.endpoint.health.show-details: never` に
してあり、DB への接続可否やディスク容量といった内部の状態を出さない（NFR-03）。
公開しているのは `health` のみで、他の actuator エンドポイントは
`management.endpoints.web.exposure.include` から外してある。

---

## 5. 管理 API

すべて `X-Admin-Api-Key` を要求する。

### 5.1 出演情報の一覧と個別取得（点検用）

```
GET /api/admin/appearances?sourceType=AUTO&page=0&size=20
```

FR-24 の点検一覧。公開 API と違い、内部項目も返す。

| パラメータ | 必須 | 説明 |
| --- | --- | --- |
| `sourceType` | — | `AUTO` / `MANUAL`。省略時は全件 |
| `page` | — | 0 始まり。既定 `0` |
| `size` | — | 既定 `20`、最大 `100` |

**レスポンス**

```json
{
  "items": [
    {
      "id": 12,
      "appearanceDate": "2026-09-16",
      "eventName": "lonlium pre.『LONELY KIDS』",
      "eventKey": "lonliumprelonelykids",
      "venueName": "愛知・大須RADHALL",
      "performanceStartTime": "19:50:00",
      "performanceEndTime": "20:15:00",
      "merchStartTime": "21:25:00",
      "merchEndTime": "22:35:00",
      "ticketUrl": "https://livepocket.jp/e/lk-nagoya0916",
      "sourceUrl": "https://x.com/.../status/...",
      "sourceType": "AUTO",
      "ingestedPostId": 55,
      "createdAt": "2026-08-20T02:00:00Z",
      "updatedAt": "2026-08-25T04:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 42
}
```

`createdAt` の降順で返す（新しく取り込まれたものから点検する）。

**個別取得**

```
GET /api/admin/appearances/{id}
```

編集画面が現在値を読むために使う。レスポンスは上の `items` の 1 要素と同じ形。
存在しない ID は `404`。

**公開 API に個別取得は用意しない。** 閲覧者向けの詳細は月一覧
（第 4.1 節）が返す項目だけで描画でき、1 か月 1 リクエストの原則（NFR-01）を崩さない。
個別取得を足すと ISR のキャッシュキーが出演情報の件数だけ増え、
T-04（無料枠の枯渇）の経路が広がる（[security.md](security.md) T-04）。
将来の EAS（第 7 章）も月一覧を使う前提でよい。

### 5.2 手動登録

```
POST /api/admin/appearances
```

**リクエスト**

```json
{
  "appearanceDate": "2026-09-20",
  "eventName": "『SAMPLE FES』",
  "venueName": "東京・SAMPLE HALL",
  "performanceStartTime": "18:00:00",
  "performanceEndTime": "18:30:00",
  "merchStartTime": "19:00:00",
  "merchEndTime": "20:00:00",
  "ticketUrl": "https://example.com/ticket",
  "sourceUrl": "https://x.com/.../status/...",
  "ingestedPostId": null
}
```

| フィールド | 必須 | 検証 |
| --- | --- | --- |
| `appearanceDate` | ○ | `YYYY-MM-DD` |
| `eventName` | ○ | 1〜200 文字 |
| `sourceUrl` | ○ | `https://` で始まる（FR-06：根拠のないデータを公開しない） |
| `venueName` | — | 300 文字以内 |
| `performanceStartTime` / `performanceEndTime` | — | `HH:mm:ss`。両方ある場合 開始 ≦ 終了 |
| `merchStartTime` / `merchEndTime` | — | `HH:mm:ss`。両方ある場合 開始 ≦ 終了。出演時刻との前後は問わない |
| `ticketUrl` | — | `http://` または `https://` で始まる |
| `ingestedPostId` | — | 未処理投稿から作る場合に指定（FR-25）。存在する投稿を指すこと。`EXCLUDED` の投稿は指定できない（`400`） |

- `eventKey` は**クライアントから受け取らない**。サーバ側で `eventName` から生成する
- **`ingestedPostId` を指定した場合、その投稿の `status` を `REGISTERED` へ進める**
  （`UNPARSED` のときだけ。既に `REGISTERED` なら変更しない）。
  これにより処理済みの投稿が未処理一覧（第 5.5 節）から消える。
  同じ投稿から 2 件目の出演情報を作る場合は一覧に出てこないため、
  出典 URL を控えたうえで本エンドポイントを直接使う
- **同じ `appearanceDate` / `eventKey` / `performanceStartTime` の組が既にある場合は
  `409`** を返す。上書きしない。既存を直したい場合は編集（第 5.3 節）を使う。
  開始時刻を含めるのは、同じ日・同じイベントで複数回出演する告知があるため
  （[data-model.md](data-model.md) 第 4.3.2 節）。
  `performanceStartTime` が未指定の行は 1 日 1 イベントにつき 1 行しか作れない
- 成功時は `201 Created` と作成されたリソースを返す

### 5.3 編集

```
PUT /api/admin/appearances/{id}
```

リクエストは第 5.2 節と同じ形。**部分更新ではなく全項目を送る**
（`PATCH` にすると「未指定」と「`null` にしたい」を区別できず、
値の消去が意図せず無視される）。

- `eventName` を変更した場合、`eventKey` はサーバ側で再計算する
- 変更後の `appearanceDate` / `eventKey` / `performanceStartTime` が他の行と衝突する場合は `409`
- 成功時は `200` と更新後のリソースを返す

### 5.4 削除

```
DELETE /api/admin/appearances/{id}
```

- 成功時は `204 No Content`
- `ingested_post` の記録は削除しない。同じ投稿から再登録されるのを防ぐため
  （[data-model.md](data-model.md) 第 7.2 節）
- 存在しない ID は `404`

### 5.5 未処理投稿の一覧

```
GET /api/admin/unparsed-posts?page=0&size=20
```

FR-25。抽出に失敗した投稿を新しい順に返す。

```json
{
  "items": [
    {
      "id": 55,
      "tweetId": "1234567890123456789",
      "postUrl": "https://x.com/.../status/1234567890123456789",
      "postedAt": "2026-08-27T12:00:00Z",
      "ingestedAt": "2026-08-27T12:15:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3
}
```

- **投稿本文を返さない**（LR-02。そもそも保持していない）。
  管理者は `postUrl` を開いて X 上で原文を読む
- `tweetId` は **文字列で返す**。JavaScript の `Number` は 53 bit までで、
  数値のまま渡すと精度が落ちる

### 5.6 未処理投稿を対象外にする

```
POST /api/admin/unparsed-posts/{id}/exclude
```

出演告知ではない投稿を一覧から外す（FR-25）。
`ingested_post.status` を `EXCLUDED` に変更する。成功時は `204`。

### 5.7 取り込み履歴

```
GET /api/admin/ingestion-runs?page=0&size=20
```

NFR-04 のコスト追跡と NFR-09 の失敗検知に使う。

```json
{
  "items": [
    {
      "id": 901,
      "startedAt": "2026-08-28T01:00:00Z",
      "finishedAt": "2026-08-28T01:00:03Z",
      "status": "SUCCESS",
      "fetchedResourceCount": 4,
      "newAppearanceCount": 1,
      "errorSummary": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2880,
  "currentMonthResourceCount": 287,
  "consecutiveFailureCount": 0,
  "halted": false
}
```

| フィールド | 説明 |
| --- | --- |
| `items` | 実行記録を**開始日時の降順**で返す。日時は UTC |
| `finishedAt` | 実行中（`status` が `RUNNING`）なら `null` |
| `errorSummary` | 失敗理由の要約。**スタックトレースとトークンを含まない**（NFR-03） |
| `currentMonthResourceCount` | 当月の `fetchedResourceCount` 合計。`× $0.005` が概算コスト（NFR-04） |
| `consecutiveFailureCount` | 直近で失敗が連続している回数。成功が 1 件でも挟まれば 0 に戻る |
| `halted` | 連続失敗で取り込みが打ち切られているか（FR-43 / NFR-09） |

**当月は JST の暦月で切る**（NFR-05）。管理者が見る「今月」は JST の暦月であり、
UTC で切ると月初 9 時間分が前月に混じる。ただしこれは**概算のための区切りであって
請求期間ではない**。X API の請求サイクルはクレジットの購入日を起点に切られ、
暦月と一致しない（[runbook-x-api-setup.md](runbook-x-api-setup.md) 第 3.3 節）。

**`halted` と `consecutiveFailureCount` はサーバ側で判定する。** 打ち切りの条件を
画面側で書き直すと、実際に取り込みを止めている条件とずれても誰も気づけない。
判定規則は `IngestionHaltRule` が 1 か所で持ち、スケジューラとこの API が同じものを使う。
表示中のページに含まれる `items` を数え直して求めるものではない
（2 ページ目を開いても判定は変わらない）。

**再開の操作はこのエンドポイントに持たせない。** 打ち切りからの復帰は
原因を確認してから手で戻す運用であり（FR-43）、押すだけで再開できると
原因が残ったまま同じ範囲を取り直して課金が積み上がる。

---

## 6. 内部 API

### 6.1 管理者パスワードの検証

```
POST /internal/auth
```

Next.js のログイン処理からのみ呼ばれる（[architecture.md](architecture.md) 第 3.2 節）。

**リクエスト**

```json
{ "password": "..." }
```

**レスポンス**

```json
{ "authenticated": true }
```

- パスワードは環境変数の BCrypt ハッシュと照合する（FR-20）
- 失敗時も `200` と `{"authenticated": false}` を返す。
  ステータスコードで成否を区別しない（総当たりの判定材料を減らす）
- **試行回数の制限は Next.js 側と Spring Boot 側の両方で行う**（FR-20, NFR-03）。
  Next.js だけだと、内部キーを持つ攻撃者が直接叩けてしまう
- リクエストとレスポンスの**どちらにもパスワードをログ出力しない**

---

## 7. 将来の EAS 対応で変わる点

モバイルアプリは Next.js を経由できないため、公開 API を直接叩く。

- **アプリに埋め込んだ `X-Api-Key` は秘密にならない。**
  そのため公開 API は「キーなしで読めるが `GET` のみ」に変更する想定
- その時点で公開 API にレート制限を必ず入れる（NFR-03）
- **管理 API はモバイルに公開しない。** 管理操作は Web の管理画面に限定する
- 公開 API のレスポンスに表示都合の値を含めないため、この変更で
  レスポンス形式を変えずに済む（NFR-07）

---

## 8. 未決定事項

1. **出演情報の個別取得 `GET /api/public/appearances/{id}`**。
   現状は月範囲の一覧に全項目が含まれるため不要。
   詳細ページを直リンクで共有したくなったら追加する
2. **公開 API のキャッシュヘッダ**。Next.js の ISR でキャッシュするため
   現状は必須でないが、EAS 対応時に `Cache-Control` の設計が要る
3. **管理 API の楽観ロック**。単一管理者のため競合しない前提。
   将来管理者が増えるなら `updatedAt` によるバージョンチェックを入れる
4. **`GET /api/admin/appearances` の絞り込み条件**。
   現状は `sourceType` のみ。点検の運用次第で「未確認のみ」などが必要になりうる
