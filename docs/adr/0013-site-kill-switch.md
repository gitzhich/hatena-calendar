# ADR-0013: サイト全体の停止を Next.js の middleware で行う

- status: 承認済み
- 日付: 2026-09-01

## 背景

[requirements.md](../requirements.md) LR-05 は、削除要請を受けた際に
**サイト全体を非公開にできる手段**を管理者が持つことを求めている。
これはリリース判定基準（[requirements.md](../requirements.md)「リリース判定基準（Definition of Done）」）にも入っているが、
実現方式が設計文書のどこにも定まっていなかった。

方式を決めるうえで効いてくる制約が 1 つある。
公開カレンダーは Vercel の ISR でキャッシュされており
（[architecture.md](../architecture.md)「キャッシュ戦略」）、
**バックエンドを止めてもキャッシュ済みのページは配信され続ける**。
Fly.io と Neon を落とす、環境変数でバックエンドの URL を外す、といった
バックエンド側の対処では公開を止められない。

## 決定

**Next.js の `proxy.ts`（旧 middleware）で止める。**

```
proxy.ts
  SITE_DISABLED === 'true' なら、/admin 配下を除く全リクエストを
  /unavailable へ rewrite する

停止手順
  1. Vercel の環境変数に SITE_DISABLED=true を設定
  2. 再デプロイ
```

## 理由

- **`proxy.ts` はキャッシュの手前で全リクエストを受ける。** ISR キャッシュ済みの
  ページも確実に止まる。上記の制約を満たす方式がこれしかない
- **依存を増やさない。** 環境変数と Next.js の標準機能だけで完結し、
  MVP を軽く保つ方針（[CLAUDE.md](../../CLAUDE.md)）に沿う
- **停止ページに削除要請の連絡先を残せる。** 要請者が状況を確認できないまま
  サイトが消える状態にしない
- **`/admin` を除外することで、止めたまま個別削除（FR-23）を進められる。**
  「サイト全体を止める」と「誤情報を消す」は同時に必要になることが多い
- 反映に再デプロイ（1〜2 分）を挟むが、LR-05 が求めるのは**手段を持つこと**であり
  即時性ではない

## 検討した代替案

| 案 | 却下理由 |
| --- | --- |
| バックエンド（Fly.io / Neon）を止める | **ISR キャッシュ済みのページが配信され続け、公開が止まらない** |
| Vercel の Deployment Protection | 即時でコード変更も要らないが、サイト全体が認証壁になり削除要請の受付や経緯を説明するページも出せない。Hobby プランで使える範囲にも依存する |
| 管理画面のトグル（Vercel Edge Config） | 再デプロイ不要で即時に切れるが、依存が 1 つ増える。MVP の軽さに見合わない |
| 管理画面のトグル（状態を DB に保持） | `proxy.ts` が毎リクエスト DB を読むことになり、**Neon の CU-hours を消費して T-04 の可用性攻撃に直撃する**（[security.md](../security.md) T-04） |

## 結果

- `SITE_DISABLED` が Next.js（Vercel）の環境変数に加わった
  （[architecture.md](../architecture.md)「設定と環境変数」）
- `frontend/proxy.ts` と停止中の案内ページ `app/unavailable/` が
  ルーティングに加わった（同 [architecture.md](../architecture.md)「ルーティング」）。
  Next.js 16 で `middleware.ts` は非推奨になり `proxy.ts` に改称された
- 停止ページに載せる連絡先は[ADR-0017](0017-contact-channel.md)で決着した。
  **停止中に到達できるのがこのページと `/admin` だけであることが、
  そちらの決定を縛っている**（サイト内のフォームは窓口にできない）
- ISR キャッシュを外す変更を入れる場合、本 ADR の前提（キャッシュ済みページが
  残る）は変わるが、`proxy.ts` で止める方式はそのまま成立する

## 関連

- [docs/requirements.md](../requirements.md) LR-05
- [docs/architecture.md](../architecture.md)「サイト全体の停止」
- [docs/security.md](../security.md)「誤った出演情報の公開が判明した場合」 / T-04

## 更新履歴

- 2026-09-03: 「停止ページに載せる連絡先が未確定」を、[ADR-0017](0017-contact-channel.md)
  で決着した旨に差し替えた。**決定内容は変えていない。**
