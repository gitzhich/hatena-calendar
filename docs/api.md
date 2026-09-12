# API 設計 — XINXIN 出演情報カレンダー

最終更新: 2026-09-03

関連文書: [CLAUDE.md](../CLAUDE.md) / [docs/requirements.md](requirements.md) /
[docs/data-model.md](data-model.md) / [docs/architecture.md](architecture.md)

---

## 1. 前提

Spring Boot が提供する REST API の契約。呼び出すのは **Next.js（BFF）のサーバ側だけ**で、
ブラウザから直接叩かれることはない（[architecture.md](architecture.md)「通信経路と認証」）。

API は 3 系統に分かれる。これに、認証を掛けない監視用エンドポイントが 1 つ加わる。

| 系統 | パス | 認証 | 用途 |
| --- | --- | --- | --- |
| 公開 API | `/api/public/**` | 公開キー | 閲覧者向け。**`GET` のみ** |
| 管理 API | `/api/admin/**` | 管理キー | 登録・編集・削除・点検 |
| 内部 API | `/internal/**` | 管理キー | 管理者パスワードの検証 |
| 監視 | `/actuator/health` | **なし** | Fly.io のヘルスチェック（本書「ヘルスチェック」） |

**これ以外のパスはすべて拒否する**（`anyRequest().denyAll()`）。
許可を明示的に列挙する形にしてあり、新しいエンドポイントは
`SecurityConfig` に足さない限り 403 になる（本書「認可の実装方針」）。

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
- 管理者パスワードの検証は `/internal/auth` でのみ行う（本書「内部 API」）

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

**ここを誤ると日付がずれる。** [data-model.md](data-model.md)「タイムゾーンの扱い」の方針に対応する。

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
| `502` | 外部サービス（Places API）に到達できない。詳細はログにのみ残す |

**`502` を `500` に混ぜない。** こちらの不具合ではなく相手側の事情であり、
「時間をおけば直るかもしれない」と分かるほうがよい。運用でも見るべき場所が変わる。

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
      "venueRegion": "CHUBU",
      "venuePlaceId": "ChIJxxxxxxxxxxxxxxxxxxxxxxx",
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
      "venueRegion": "KANTO",
      "venuePlaceId": null,
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
  （内部の実装詳細であり、画面に出す値ではない。[data-model.md](data-model.md)「同一イベントの一意性と event_key」）
- `sourceType`、`createdAt`、`ingestedPostId` も公開 API では返さない。
  閲覧者に不要な内部情報を出さない
- 並び順は `appearanceDate` 昇順、次に `performanceStartTime` 昇順。
  **時刻が `null` のものは同じ日付の末尾**に置く（FR-03）
- **同じ日に同じ `eventName` が複数並ぶことがある。** 1 つのイベントの中で
  複数回出演する告知があるため（[x-integration.md](x-integration.md)「1 投稿から複数の出演情報」）。
  会場と時刻で区別できる
- **`venueRegion`** は会場の地域（FR-10）。`HOKKAIDO` / `TOHOKU` / `KANTO` / `CHUBU` /
  `KINKI` / `CHUGOKU` / `SHIKOKU` / `KYUSHU` / `OVERSEAS` / `UNKNOWN` のいずれか。
  **会場が空欄なら `UNKNOWN`。** 判定の規則は
  [data-model.md](data-model.md)「venue — 会場」
- **`venuePlaceId`** は Google の場所 ID（FR-09）。**`null` は「まだ同定できていない」。**
  クライアントは `null` のとき、名前で検索する地図リンクに落とす
  （[ADR-0022](adr/0022-venue-place-id-and-region.md)）
- **地図の URL は返さない。** URL の書式は表示側の都合であり、
  変わったときにバックエンドのデプロイを要求したくない。
  返すのは識別子までで、組み立てはクライアントが行う
- **色は返さない。** どの地域を何色にするかは見た目の決定で、API の関心事ではない
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
| `sort` | — | 並び順。既定 `DATE_DESC` |
| `page` | — | 0 始まり。既定 `0` |
| `size` | — | 既定 `20`、最大 `100` |

**並び順**

| `sort` | 順序 |
| --- | --- |
| `DATE_DESC`（既定） | 開催日 → 出演開始時刻の**降順** |
| `DATE_ASC` | 開催日 → 出演開始時刻の**昇順** |
| `CREATED_DESC` | 登録日時の降順 |
| `CREATED_ASC` | 登録日時の昇順 |

**どの順序も最後に `id` で決着させる。** 同じ値の行が並ぶと順序が決まらず、
ページの境界で取りこぼしと重複が出る。

**出演開始時刻が未設定の行は、昇順でも降順でも最後に置く**（`NULLS LAST`）。
向きによって未設定の位置が入れ替わると、同じ「時刻未定」の行が
並べ替えのたびに端から端へ飛ぶ。

**`sort` は知らない値を既定に倒す。`400` にしない。**
並び順は表示の都合でしかなく、古いリンクを開いただけで画面が止まるほうが困る。

**`page` / `size` も範囲外を丸める。`400` にしない。**

| 入力 | 結果 |
| --- | --- |
| `page` が負 | `0` |
| `size` が 1 未満 | `1` |
| `size` が 100 超 | `100` |

本書「エラー」の「`400`: クエリパラメータの形式不正」は**型として解釈できない場合**
（`size=abc` など）を指す。数として読めるが範囲外の値は丸める。
点検一覧はページャから呼ばれるだけで、値がずれても**画面が止まらないほうが望ましい**。
上限を返す理由（無制限に大きな `size` で DB を引かせない）は丸めれば達成される。

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
      "areaName": null,
      "venueId": 3,
      "venueRegion": "CHUBU",
      "venuePlaceId": "ChIJxxxxxxxxxxxxxxxxxxxxxxx",
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
（本書「期間内の出演情報一覧」）が返す項目だけで描画でき、1 か月 1 リクエストの原則（NFR-01）を崩さない。
個別取得を足すと ISR のキャッシュキーが出演情報の件数だけ増え、
T-04（無料枠の枯渇）の経路が広がる（[security.md](security.md) T-04）。
将来の EAS（本書「将来の EAS 対応で変わる点」）も月一覧を使う前提でよい。

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
  "areaName": null,
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
  これにより処理済みの投稿が未処理一覧（本書「未処理投稿の一覧」）から消える。
  同じ投稿から 2 件目の出演情報を作る場合は一覧に出てこないため、
  出典 URL を控えたうえで本エンドポイントを直接使う
- **同じ `appearanceDate` / `eventKey` / `performanceStartTime` の組が既にある場合は
  `409`** を返す。上書きしない。既存を直したい場合は編集（本書「編集」）を使う。
  開始時刻を含めるのは、同じ日・同じイベントで複数回出演する告知があるため
  （[data-model.md](data-model.md)「同一イベントの一意性と event_key」）。
  `performanceStartTime` が未指定の行は 1 日 1 イベントにつき 1 行しか作れない
- 成功時は `201 Created` と作成されたリソースを返す

### 5.3 編集

```
PUT /api/admin/appearances/{id}
```

リクエストは本書「手動登録」と同じ形。**部分更新ではなく全項目を送る**
（`PATCH` にすると「未指定」と「`null` にしたい」を区別できず、
値の消去が意図せず無視される）。

**ただし `ingestedPostId` は読み取り専用で、送っても無視される。**
この値は「最後に内容を反映した告知」を指す導出値であり、
`sourceUrl` と常に同じ投稿を指す（[data-model.md](data-model.md)「追加告知による空欄補完」）。
編集で付け替えられるようにすると、2 つが別の投稿を指せてしまう。
400 で弾かずに無視するのは、`GET`（本書「出演情報の一覧と個別取得（点検用）」）で受け取った値を
そのまま返す往復を壊さないため。

紐付けを直したいときは削除して作り直す。手順は
[data-model.md](data-model.md)「削除と冪等性」。

- `eventName` を変更した場合、`eventKey` はサーバ側で再計算する
- 変更後の `appearanceDate` / `eventKey` / `performanceStartTime` が他の行と衝突する場合は `409`
- **`areaName` は会場が未定のときの地名**（`東京`）。`venueName` が入っていれば
  そちらから地域を引くので、**両方を入れる必要は無い**
  （[ADR-0022](adr/0022-venue-place-id-and-region.md)「会場が未定でも地域は持つ」）。
  取り込みが会場を空欄にした行へ、管理者が後から地名だけ入れる用途を想定している
- 成功時は `200` と更新後のリソースを返す

### 5.4 削除

```
DELETE /api/admin/appearances/{id}
```

- 成功時は `204 No Content`
- `ingested_post` の記録は削除しない。`status` も `REGISTERED` のまま動かさない。
  **削除した投稿は未処理一覧に戻らない**（[data-model.md](data-model.md)「削除と冪等性」）
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
      "unparsedCount": 2,
      "truncated": false,
      "errorSummary": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2880,
  "currentCycleResourceCount": 287,
  "cycleStartAt": "2026-09-01T15:00:00Z",
  "consecutiveFailureCount": 0,
  "halted": false
}
```

| フィールド | 説明 |
| --- | --- |
| `items` | 実行記録を**開始日時の降順**で返す。日時は UTC |
| `finishedAt` | 実行中（`status` が `RUNNING`）なら `null` |
| `status` | `RUNNING` / `SUCCESS` / `FAILED` / `CANCELLED`。`CANCELLED` は**管理者が原因を確認し、打ち切りカウントから外した失敗**（[runbook-x-api-setup.md](runbook-x-api-setup.md)「打ち切りから戻す」）。連続失敗の判定はここで切れる |
| `unparsedCount` | この実行で未処理にした投稿の件数（FR-25）。**`null` は「0 件」ではなく「分からない」**——列を足す前の実行記録と、失敗した実行がこれに当たる |
| `truncated` | ページ上限で打ち切ったか。`true` なら**古い投稿を取りこぼしている**（[x-integration.md](x-integration.md)「ページング」 / [ADR-0020](adr/0020-drop-posts-beyond-page-limit.md)）。`status` は `SUCCESS` のまま |
| `errorSummary` | 失敗理由の要約。**スタックトレースとトークンを含まない**（NFR-03） |
| `currentCycleResourceCount` | 現在の請求サイクルの `fetchedResourceCount` 合計。`× $0.005` が概算コスト（NFR-04） |
| `cycleStartAt` | 集計期間の開始（UTC）。何を合計した値かを画面が示せるようにする |
| `consecutiveFailureCount` | 直近で失敗が連続している回数。成功が 1 件でも挟まれば 0 に戻る |
| `halted` | 連続失敗で取り込みが打ち切られているか（FR-43 / NFR-09） |

**集計期間は請求サイクルで切る。暦月ではない。** X API の請求サイクルは
クレジットの購入日を起点に切られる（例: `Sep 2 - Oct 2`。
[runbook-x-api-setup.md](runbook-x-api-setup.md)「コンソールで紛らわしい点」）。暦月で切ると
支出上限のリセット日と集計期間がずれ、NFR-04 の「想定を超えたら気づける」が
成り立たない。

- 起点の日は設定値 `x.billing-cycle-start-day`（1〜31）で持つ。
  **`1` を指定すると暦月と一致する**ので、暦月は特殊ケースであって別の分岐ではない
- その日が無い月（31 起点の 2 月）は**月末に丸める**
- **境界は JST で切る**（NFR-05）。UTC で切ると 9 時間分が前のサイクルに混じる
- 正確な請求額は X の管理画面で確認する。ここに出すのは概算

**`halted` と `consecutiveFailureCount` はサーバ側で判定する。** 打ち切りの条件を
画面側で書き直すと、実際に取り込みを止めている条件とずれても誰も気づけない。
判定規則は `IngestionHaltRule` が 1 か所で持ち、スケジューラとこの API が同じものを使う。
表示中のページに含まれる `items` を数え直して求めるものではない
（2 ページ目を開いても判定は変わらない）。

**再開の操作はこのエンドポイントに持たせない。** 打ち切りからの復帰は
原因を確認してから手で戻す運用であり（FR-43）、押すだけで再開できると
原因が残ったまま同じ範囲を取り直して課金が積み上がる。
戻すのは DB を直接触る操作で、手順は
[runbook-x-api-setup.md](runbook-x-api-setup.md)「打ち切りから戻す」にある。

---

### 5.8 会場の一覧と編集

```
GET    /api/admin/venues?page=0&size=20&unresolved=true
GET    /api/admin/venues/{id}
PUT    /api/admin/venues/{id}
DELETE /api/admin/venues/{id}
POST   /api/admin/venues/{id}/resolve-place-id
```

会場ごとに 1 行を持ち、地域と `place_id` を管理する
（[ADR-0022](adr/0022-venue-place-id-and-region.md) / [data-model.md](data-model.md)「venue — 会場」）。

| パラメータ | 必須 | 説明 |
| --- | --- | --- |
| `unresolved` | — | `true` なら **`place_id` が未解決の会場だけ**返す。初期投入の進み具合と、解決できない会場の確認に使う |
| `page` / `size` | — | 点検一覧と同じ丸め規則（本書「出演情報の一覧と個別取得（点検用）」） |

**レスポンス**

```json
{
  "items": [
    {
      "id": 3,
      "venueKey": "愛知大須radhall",
      "displayName": "愛知・大須RADHALL",
      "region": "CHUBU",
      "placeId": "ChIJxxxxxxxxxxxxxxxxxxxxxxx",
      "placeIdCheckedAt": "2026-09-08T02:00:00Z",
      "manuallyEdited": false,
      "areaOnly": false,
      "appearanceCount": 12
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 15
}
```

- **`appearanceCount`** は**この会場を指す出演情報の件数**。直す価値の大きさが分かる。
  1 行直せば全件に効く
- `venueKey` は返すが**編集できない**。表記から機械的に決まる値であり、
  変えると別の会場に化ける
- **`areaOnly` が `true` の行は会場ではなく地域**（`東京`）。会場が未定の出演情報が
  指している（[ADR-0022](adr/0022-venue-place-id-and-region.md)「会場が未定でも地域は持つ」）。
  `place_id` を解決せず、**`resolve-place-id` は `409`** を返す。
  `appearanceCount` は「会場が未定のままの公演が何件あるか」として読める
- 並び順は **`region`（北海道 → 東北 → 関東 → 中部 → 近畿 → 中国 → 四国 → 九州 →
  海外 → 不明）→ `displayName` → `id`**。地方でまとまるので、同じ地域の会場を
  見比べながら直せる。`region` は文字列で保存しているため、素直に並べると
  アルファベット順（CHUBU, CHUGOKU, HOKKAIDO…）になり、固まりはするが並びが恣意的になる
- **最後に `id` で決着させる。** 一意に決めておかないとページの境界で
  取りこぼしと重複が出る（本書「出演情報の一覧と個別取得（点検用）」と同じ理由）
- `unresolved=true` は **`manuallyEdited` の行も返す**。管理者が誤った `placeId` を
  消した行はまさに未解決の会場であり、消えると直したい行を見失う

**1 件（`GET /{id}`）**

**一覧の 1 要素と同じ形**（`appearanceCount` 込み）を返す。編集画面が現在値を
読むための口で、画面が一覧と詳細で別の形を扱わずに済む。
存在しない `id` は `404`。

**編集（`PUT`）**

`displayName` / `region` / `placeId` を更新できる。

**`PUT` は全項目の差し替え。** `placeId` を省いて送ると `null` になり、
**解決済みの会場が未解決へ戻る**。画面から送るときは、触らない項目も現在値を載せる。

- **更新すると `manuallyEdited` が `true` になる。** 以後、自動判定と自動解決は
  **この行を上書きしない**。人が確認した値のほうが強い
- `placeId` に `null` を送ると解決前に戻す（誤って解決した場合の取り消し）

**編集した会場は、以後 `placeId` が自動では埋まらない。** `manuallyEdited` を
下ろす手段は用意していない。誤った地図リンクはリンクが無いより悪く、
**ファンが違う場所へ向かう**（[security.md](security.md) T-08）。
自動解決が外したから人が直したのに、翌日また同じ値で埋め直されては意味がない。
正しい `placeId` が分かったら `PUT` で直接入れる。分からなければ `null` のままでよく、
地図リンクは名前検索に落ちる。

**削除（`DELETE /{id}`）**

**`appearanceCount` が 0 の会場だけ消せる。** 1 件でも参照されていれば `409` を返し、
何件あるかを `detail` に入れる。参照されている会場を消すと、**公開ページから地域も
地図リンクも一緒に落ちる**。消す対象は、会場名の書き換えで取り残された行に限られる。

- **`areaOnly` でも `manuallyEdited` でも消せる。** 条件は使用件数だけ
- ただし**人が直した地域も一緒に消える**。同じ表記が再び告知に出れば、自動判定で
  作り直される。出演 0 件の行に限られるため、公開されている情報は変わらない
- **数えてから消すまでの間に増えることがある。** 取り込みの定期実行が 30 分ごとに
  `venue_id` を付けるため。外部キー違反も `409` に落とす（`500` に混ぜない）
- 成功は `204`

**解決（`POST .../resolve-place-id`）**

**定常運用でこれを叩く必要は無い。** 解決は 1 日 1 回の定期実行が自動で進める
（[ADR-0022](adr/0022-venue-place-id-and-region.md)）。この操作は
**再試行の間隔（7 日）を待たずに今すぐ試したいとき**のためにある。

Places API の Text Search (IDs Only) を 1 件だけ呼び、`place_id` を保存する。

- **見つからなくても `placeIdCheckedAt` を更新する。** 記録しないと、
  見つからない会場を叩き続けることになる
- 見つからなければ `placeId` は `null` のまま。**推測で近い施設を入れない**
- **Google に到達できなかったときは記録せず `502` を返す。**
  「見つからなかった」（`200`）と区別する。試せていないのに記録すると、
  障害が明けても**再試行が 7 日先へ飛ぶ**
- `manuallyEdited` が `true` の会場に対しては**何もせず `409`**。
  人が入れた値を機械が消さない
- **取り込みジョブからは呼ばない**（ADR-0022）。X API の取り込みが
  Google 側の障害で失敗するのを避ける

---

## 6. 内部 API

### 6.1 管理者パスワードの検証

```
POST /internal/auth
```

Next.js のログイン処理からのみ呼ばれる（[architecture.md](architecture.md)「管理者の認証フロー」）。

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
- **その時点で公開 API の総量制限の数え方を見直す。**
  `PublicApiRateLimitFilter` は「**キーの検証を通ったものだけ数える**」設計で
  （[security.md](security.md)「レート制限の構成と値」）、キーなしで読めるようにすると
  この条件が成立せず、**300 req/分の総量制限が実質無効になる**。
  数える対象を変えるか、別の絞り方に置き換えるかを決めてから公開する
- **管理 API はモバイルに公開しない。** 管理操作は Web の管理画面に限定する
- 公開 API のレスポンスに表示都合の値を含めないため、この変更で
  レスポンス形式を変えずに済む（NFR-07）

---

## 8. 未決定事項

**番号で参照しない**（項目を消すと番号がずれる）。他の文書からは**項目名**で参照する。

1. **公開 API のキャッシュヘッダ**。Next.js の ISR でキャッシュするため
   現状は必須でないが、EAS 対応時に `Cache-Control` の設計が要る
2. **管理 API の楽観ロック**。単一管理者のため競合しない前提。
   将来管理者が増えるなら `updatedAt` によるバージョンチェックを入れる
3. **`GET /api/admin/appearances` の絞り込み条件**。
   現状は `sourceType` のみ。点検の運用次第で「未確認のみ」などが必要になりうる
