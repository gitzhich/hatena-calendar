# 手順書 — 開発用 DB への接続（DBeaver）

最終更新: 2026-09-02

関連文書: [CLAUDE.md](../CLAUDE.md) / [data-model.md](data-model.md) 第 6 章 /
[architecture.md](architecture.md) 第 7 章

---

## 0. この文書の位置づけ

ローカルの PostgreSQL に GUI クライアントから接続し、中身を確認するまでの**作業手順**。
スキーマの設計そのものは [data-model.md](data-model.md) にあり、ここでは繰り返さない。

対象は `compose.yaml` が立てる**開発用の PostgreSQL 17** のみ。
本番（Neon）への接続はこの手順の範囲外で、接続情報の扱いも異なる。

**MySQL Workbench では接続できない。** DB は PostgreSQL であり、
Workbench は MySQL プロトコル専用のクライアントで、PostgreSQL 用のドライバを
追加する仕組みを持たない。本番の Neon も PostgreSQL なので、
PostgreSQL 対応のクライアントで揃える。

### 0.1 クライアントの選択

| ツール | 備考 |
| --- | --- |
| **DBeaver Community**（本書が前提とする） | 無料・多 DB 対応。後で Neon に繋ぐときも同じものを使える |
| pgAdmin 4 | PostgreSQL 公式。機能は厚いが UI の癖が強い |
| `psql` | 追加インストール不要。`docker compose exec db psql -U hatenacal -d hatenacal` |

---

## 1. 前提

コンテナが動いていること。止まっていれば起動する。

```bash
docker compose up -d --wait      # 起動（healthy になるまで待つ）
docker compose ps                # 状態の確認
```

`Up ... (healthy)` かつ `0.0.0.0:5432->5432/tcp` が出ていれば接続できる。

**`docker compose` を叩くと `.env` の `$` を変数参照として展開しようとし、
警告が出る**（`ADMIN_PASSWORD_HASH` の bcrypt ハッシュが `$2b$12$...` の形のため）。
`compose.yaml` はこの値を使っておらず実害はないが、
警告を出したくなければ `--env-file /dev/null` を付ける
（[architecture.md](architecture.md) 第 7.1 節）。

---

## 2. 接続情報

`compose.yaml` に平文で書かれた**ローカル専用の値**。本番の認証情報ではないため、
この文書に載せてよい。

| 項目 | 値 |
| --- | --- |
| ホスト | `localhost` |
| ポート | `5432` |
| データベース | `hatenacal` |
| ユーザー | `hatenacal` |
| パスワード | `hatenacal` |
| SSL | 不要（`disable`） |

JDBC URL は `jdbc:postgresql://localhost:5432/hatenacal`。
これは `application.yml` の `DATABASE_URL` の既定値でもある。

**`.env` で `DATABASE_URL` を設定している場合はそちらが優先される。**
バックエンドと GUI で違う DB を見ていると、片方の変更がもう片方に現れず混乱する。

---

## 3. DBeaver での接続手順

以下の画面名・項目名は DBeaver 25 系のもの。バージョンによって表記が変わることがあるが、
入れる値（第 2 章）は変わらない。

1. **「新しいデータベース接続」**（`Ctrl+Shift+N`）
2. 一覧から **PostgreSQL** を選ぶ → 次へ
3. **Main** タブに第 2 章の値を入力する
4. **「パスワードを保存」にチェック**を入れる
5. 初回はドライバの取得を求められるので **ダウンロード**（1 回だけ）
6. **「テスト接続」** で成功を確認 → **完了**

テーブルは `hatenacal > スキーマ > public > テーブル` の下に出る。

---

## 4. 必ず入れる設定

### 4.1 読み取り専用にする

接続の作成時、または「接続の編集」→ **一般** → **「読み取り専用接続」にチェック**。

**閲覧目的ならこれを外さない。** 誤操作の代償がこのプロジェクトでは金銭に直結する。

- `source_account.last_fetched_tweet_id` を書き換えると、次の取り込みで
  **取得範囲が広がって課金が跳ねる**。過去に、テストが残した値のまま動かせば
  $5.00 になる状況が起きている
- `ingestion_run` は課金額を後から追跡するための台帳
  （[data-model.md](data-model.md) 第 4.4 節）。消すと追跡できなくなる

値を書き換える必要が出たら、**何をどう戻すかを決めてから**読み取り専用を外す。

### 4.2 タイムゾーンの見え方を把握する

**このプロジェクトは保存形式を 2 種類に分けている**（[data-model.md](data-model.md) 第 6 章）。
GUI はこの 2 つを見た目で区別しないため、同じ物差しで読むとズレる。

| 列の例 | 型 | 実際の保存 | DBeaver での見え方 |
| --- | --- | --- | --- |
| `created_at` / `started_at` / `finished_at` | `TIMESTAMPTZ` | UTC | **クライアントの JST に変換される** |
| `appearance_date` / `performance_start_time` / `merch_start_time` | `DATE` / `TIME` | JST のローカル値 | **変換されない。そのまま** |

つまり `ingestion_run.finished_at` が `17:25` と見えても、DB 上は `08:25 UTC`。
一方 `performance_start_time` の `14:30` は変換のかかっていない JST。

生の値で確認したいときは
`設定 > エディター > データエディター > データフォーマット` のタイムゾーンを UTC にする。

---

## 5. テーブルの見どころ

| テーブル | 中身 |
| --- | --- |
| `appearance` | 出演情報の本体（[data-model.md](data-model.md) 第 4.3 節） |
| `ingestion_run` | 取り込みの実行ログ。**課金追跡の台帳**。`fetched_resource_count` が課金単位そのもの |
| `ingested_post` | 取り込んだ投稿。`status` が `UNPARSED` なら管理画面の未処理一覧に出る |
| `source_account` | **`last_fetched_tweet_id` が課金に直結する。触らない** |
| `flyway_schema_history` | マイグレーションの適用履歴 |

当月の課金額の目安は次で出せる（請求サイクルは購入日起点で暦月と一致しないため、
期間は自分で指定する）。

```sql
SELECT sum(fetched_resource_count) AS resources,
       sum(fetched_resource_count) * 0.005 AS usd
  FROM ingestion_run
 WHERE started_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00';
```

---

## 6. うまくいかないとき

| 症状 | 対処 |
| --- | --- |
| `localhost` で繋がらない | Host を WSL の IP（`hostname -I` の 1 つ目）に変える。**再起動で変わる** |
| `bootRun` が起動時に止まる | DBeaver が手動コミットで未コミットのトランザクションを抱えていると、Flyway がロック待ちになる。自動コミットにするか、読み取り専用にしておけば起きない |
| テーブルが見えない | スキーマ `public` の下を見る。DBeaver は既定で接続先のデータベースのみ表示する |
| 接続はできるが行が無い | バックエンドを一度も起動していない可能性がある。Flyway は `bootRun` の起動時に適用される |

---

## 7. 補足

**`./gradlew test` はこの DB を使わない。** Testcontainers が使い捨ての PostgreSQL を
実行ごとに立てるため（`PostgresContainerListener`）、**DBeaver で接続したまま
テストを流してもこの DB のデータは消えない**。

以前は結合テストが各テーブルを削除しており、`./gradlew test` のたびに
`source_account` まで消えていた。行が消えたことに気づかず取り込みを動かすと
取得範囲が意図せず広がるため、Testcontainers に切り替えて解消してある。

**バックエンドを起動しっぱなしにしない。** `bootRun` を止め忘れると
取り込みが 30 分ごとに動き続け、新規投稿があればそのぶん課金される。
取り込みを動かす意図がない確認では
`./gradlew bootRun --args='--ingestion.enabled=false'` で起動する。
