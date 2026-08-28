# ADR-0007: マイグレーションに Flyway を採用する

- status: 承認済み
- 日付: 2026-08-28

## 背景

PostgreSQL のスキーマ変更をどう管理するか。
Spring Boot では Flyway、Liquibase、JPA の自動 DDL が主な選択肢になる。

## 決定

**Flyway を使う。** 番号付き SQL ファイルを
`backend/src/main/resources/db/migration/` に置く。
`spring.jpa.hibernate.ddl-auto` は **`validate` に固定**する。

## 理由

- 今回は **PostgreSQL 単一・単一開発者・テーブル 4 つ**。
  Liquibase の DB 抽象化が利点にならず、記述コストだけが増える
- SQL をそのまま書くので、**スキーマ変更が git の差分として読める**
- Spring Boot の公式統合があり、設定が数行で済む
- ロールバックは Community 版の対象外だが、この規模なら
  「打ち消すマイグレーションを追加する」で足りる

## 検討した代替案

| 案 | 却下理由 |
| --- | --- |
| Liquibase | XML/YAML の記述が冗長。抽象化レイヤーの学習コストに見合う利点がない |
| **JPA の自動 DDL（`ddl-auto=update`）** | 本番で使えない（下記） |

**`ddl-auto=update` を退けた理由。** カラムのリネームや削除を反映しないため、
消したはずの列がテーブルに残り続ける。そこへ旧バージョンのアプリが書き込むと
データが二重管理になって壊れる。変更履歴も残らない。

## 結果

- 適用済みのマイグレーションファイルを**後から編集しない**
  （Flyway がチェックサム不一致で起動を止める）
- 取り消しが必要な場合は打ち消すマイグレーションを新しい番号で追加する
- Flyway Community は無料で商用利用可。ロールバック・ドリフト検出は
  Enterprise 限定である点を承知の上で採用する

## 関連

- [docs/data-model.md](../data-model.md) 第 8 章
