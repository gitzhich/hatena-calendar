# ADR-0008: Next.js を BFF とする

- status: 承認済み
- 日付: 2026-08-28

## 背景

ブラウザからバックエンドへの経路をどうするか。
Next.js を単なる画面提供に留めてブラウザから Spring Boot を直接呼ぶか、
Next.js をバックエンド呼び出しの中継役（BFF）にするか。

## 決定

**ブラウザ ↔ Next.js ↔ Spring Boot。ブラウザは Spring Boot を直接呼ばない。**

## 理由

- 公開ページを **Server Component + ISR** で返せる。初回表示が速く（NFR-01）、
  キャッシュにより DB アクセスが減る
- **CORS が不要**になり、設定ミスによる事故の余地が減る
- 認証を**同一オリジンの Cookie** で扱える。クロスオリジンの
  `SameSite` 調整が不要
- Spring Boot をブラウザから見えない位置に置ける

## 検討した代替案

**ブラウザから Spring Boot を直接叩く。** 構成は単純で、Web とモバイルが
同じ経路になる利点がある。ただし CORS 設定が必要で、
Spring Boot が最初からインターネットに露出する。

## 結果

- **モバイル（EAS）は BFF を経由できない。** Next.js はウェブ専用のため、
  モバイルは Spring Boot の公開 API を直接叩くことになる。
  その時点で公開 API の認証方式を見直す必要がある
  （アプリに埋め込んだキーは秘密にならないため、
  「キーなしで読めるが `GET` のみ」へ変更する想定）
- 公開 API のレスポンスに表示都合の値を含めない制約が生まれた（NFR-07）
- **Spring Boot は結局インターネットに露出する。** Vercel から呼ぶため。
  IP 制限は Vercel の送信 IP が固定されず使えないので、
  内部 API キーで補う（[ADR-0010](0010-split-api-keys.md)）
- ISR キャッシュが可用性の防御としても機能することが後から判明した
  （[docs/security.md](../security.md) T-04）

## 関連

- [docs/architecture.md](../architecture.md) 第 1 章、第 5 章
