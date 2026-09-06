# ADR-0016: 公開ページは静的 CSP、管理画面は nonce ベースの CSP にする

- status: 承認済み
- 日付: 2026-09-02

## 背景

NFR-03 と [security.md](../security.md)「対策が効く場所の一覧」はレスポンスヘッダに
CSP を設定することを求めている。[security.md](../security.md)「未決定事項」の未決定事項にあった
**「CSP の適用範囲」**は
「Next.js のインラインスクリプトとの兼ね合いで `nonce` の設定が必要になる。
実装時に詰める」としていた。

実装時に Next.js の CSP ガイド（`node_modules/next/dist/docs/01-app/02-guides/
content-security-policy.md`）を読んで、**nonce と ISR が両立しない**ことが分かった。

> Static optimization and Incremental Static Regeneration (ISR) are disabled

nonce はリクエストごとに変わるため、ビルド時に生成した HTML に埋め込めない。
nonce を使うページは必ず動的レンダリングになる。

これは正面から衝突する。ISR は [ADR-0014](0014-bounded-calendar-range.md) と
[security.md](../security.md) T-04 における**可用性の主防御**であり、
CSP を強くするために捨てられるものではない。
T-04 は「攻撃者が公開ページを連打して Neon の無料枠を枯渇させ、
課金を発生させずにサイトを停止させられる」脅威である。

## 決定

**CSP を 2 系統に分ける。**

| 対象 | 方式 | ISR |
| --- | --- | --- |
| 公開ページ | 静的 CSP（`script-src` に `'unsafe-inline'`） | **維持** |
| `/admin` 配下 | nonce ベース（`'nonce-…' 'strict-dynamic'`） | 失わない |

`/admin` は Cookie を読むためもともと動的レンダリングであり、
nonce を使っても失うものがない。

**あわせてセッション Cookie の `path` を `/admin` に絞る。**
公開ページ側で `script-src` を緩めることの実害を、ここで打ち消す。

**CSP は `proxy.ts` で 1 度だけ付ける。** `next.config.ts` からも出すと
`Content-Security-Policy` が 2 本になり、ブラウザは**両方を同時に適用する**
（許可の積集合）。片方を緩めたつもりがもう片方で落ちる、という読めない挙動になる。
常時付けるヘッダ（HSTS / `nosniff` / `Referrer-Policy`）は `next.config.ts` に置く。
あちらは `proxy.ts` の matcher が除外する静的アセットにも付くため、
JS / CSS に `nosniff` を効かせられるのはその経路しかない。

## 理由

**CSP はディレクティブの束であり、`'unsafe-inline'` で緩むのは `script-src` だけ。**

| ディレクティブ | 守るもの | 静的 CSP でも効くか |
| --- | --- | --- |
| `frame-ancestors 'none'` | クリックジャッキング | 効く |
| `connect-src 'self'` | XSS 後のデータ持ち出し | 効く |
| `form-action 'self'` | フォームの外部送信 | 効く |
| `base-uri 'self'` | 相対 URL の乗っ取り | 効く |
| `object-src 'none'` | プラグイン経由の実行 | 効く |
| `img-src 'self' data:` | 外部へのビーコン | 効く |
| `script-src` | スクリプト注入の実行 | **ここだけ緩む** |

**公開ページにスクリプトを注入する経路が塞がっている。**

- 公開ページに入力を受け取るフォームが無い
- X 由来のテキストは React が自動エスケープする（`dangerouslySetInnerHTML` 不使用）
- URL は DB の `CHECK (… ~ '^https?://')` と PostParser が `javascript:` を弾く
- 外部スクリプトを 1 つも読み込んでいない（CDN 依存ゼロ。フォントは
  `next/font` でビルド時に自己ホストし、CSP の `font-src` は `'self'` のまま）

**残っていた唯一の経路が Cookie だった。** セッション Cookie は `path: "/"` で
発行されており、公開ページにも送られていた。`HttpOnly` は
「JS から値を読めない」だけで、**ブラウザが自動で付けて送ることは止められない**。
公開ページで XSS が成立すれば、同一オリジンで `/admin` を読み、
CSRF トークンを取り出して管理操作を実行できる。

`path` を `/admin` に絞ればこの経路は消える。公開ページはセッションを読まないため、
絞っても失うものがない。**ISR を捨てるより遥かに安い対処**である。

## 検討した代替案

| 案 | 却下理由 |
| --- | --- |
| 全ページを nonce ベースにする | **ISR が無効化される。** T-04 の主防御を失い、Neon の無料枠が公開ページの連打で枯渇しうる。CSP を強くするために可用性を落とす取引になっていて、脅威モデルと逆行する |
| 全ページを静的 CSP にする | `proxy.ts` に分岐が要らず最も単純だが、セッションと CSRF トークンがある `/admin` を弱いままにする理由がない。あちらは動的レンダリングなので nonce の代償がゼロ |
| `experimental.sri`（ハッシュベース） | 静的生成を保ったまま厳格な CSP にできる。ただし Next.js 公式が experimental と明記し「変更または削除される可能性がある」としている。インラインスクリプトの扱いも文書上明確でない。**完成を最優先する方針**（[CLAUDE.md](../../CLAUDE.md)）に対して検証コストが読めない |
| CSP を入れない | チェックリストの項目を落とすだけでなく、`frame-ancestors` などスクリプトと無関係な防御まで捨てることになる |

## 結果

- `frontend/lib/security-headers.ts` に CSP の組み立てを集約した。
  `nonce` の有無で 2 系統を出し分ける。単体テストで検証する
- `frontend/proxy.ts` が CSP を付ける。`/admin` だけ nonce を生成し、
  **リクエストヘッダにも載せる**（Next.js がそこから nonce を取り出して
  自身の script / style に付ける）
- `frontend/next.config.ts` は HSTS / `nosniff` / `Referrer-Policy` のみ
- セッション Cookie の `path` が `/admin` になった。
  **`set` と `delete` で同じ値を使うこと。** 食い違うとログアウトで消えない
- 開発時は `'unsafe-eval'` を許す（React が eval でエラースタックを復元するため）。
  `upgrade-insecure-requests` は本番だけに付ける。
  ローカルは HTTP で動かすため、開発で付けると開発サーバに繋がらなくなる
- [security.md](../security.md)「未決定事項」の未決定事項
  **「CSP の適用範囲」**が決着した（項目は削除済み）

### 実測（2026-09-02、本番ビルドをローカルで起動して確認）

- `/` は `Revalidate 5m` の静的ページのまま。**ISR が保たれている**
- `/admin/login` の script タグ 12 個すべてに nonce が付き、
  付いていないものは 0 個だった
- 公開ページの script タグには nonce が付かない（静的 CSP のため意図どおり）
- `/_next/static/` 配下にも `nosniff` と HSTS が付く

## 更新履歴

- 2026-09-03: **参照の書き方を直した**（決定内容の変更なし）。
  「未決定事項 N」という番号での参照が、**項目の削除で番号がずれて別の項目を
  指していた**。項目名での参照に置き換えた。各文書の未決定事項の章にも
  「番号で参照しない」を注記した
- 2026-09-06: 公開ページのフォントを `next/font` 自己ホストにしたことに合わせ、
  理由欄の「システムフォント」を現状に直した（決定内容の変更なし。CSP は据え置き）

## 関連

- [docs/security.md](../security.md)「対策が効く場所の一覧」 / [security.md](../security.md)「実装チェックリスト」 / T-02 / T-04
- [docs/requirements.md](../requirements.md) NFR-03
- [ADR-0014](0014-bounded-calendar-range.md)（ISR が可用性の防御である理由）
- [ADR-0013](0013-site-kill-switch.md)（`proxy.ts` の役割）
