<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

---

# フロントエンドの作業規約

**先にリポジトリ直下の `AGENTS.md` を読むこと。** ここはそれに足りない、
`frontend/` 固有の制約だけを持つ。

## 破ると本番でだけ壊れるもの

**ローカルでは再現しない。** 開発サーバは CSP を緩めて動かしているため、
次の 3 つは実装時に意識しないと気づけない。定義は `frontend/lib/security-headers.ts`。

### 外部から何も読み込めない

```
font-src 'self'          Google Fonts などの CDN 直リンクは落ちる
img-src  'self' data:    外部画像・外部アイコン CDN は落ちる
connect-src 'self'       外部 API への fetch は落ちる
```

Web フォントを使うなら **`next/font` で自己ホスト**する（ビルド時に取り込まれ、
同一オリジンから配信されるので `font-src 'self'` を満たす）。
アイコンは **SVG をインライン展開する形**にする。

### 公開ページで nonce を使わない

nonce を使うとページが動的レンダリングになり、**ISR が無効化される**。
ISR は無料枠の枯渇による可用性攻撃への主防御なので
（`docs/security.md`、[ADR-0016](../docs/adr/0016-static-csp-public-nonce-admin.md)）、
壊すと**サイトが止まりうる**。

`cookies()` / `headers()` を公開ページのレンダリング経路で呼ばない。
Client Component は末端に限り、ページ全体を `"use client"` にしない。

### `/admin` はスタイルの CSP が厳しい

公開ページは `style-src 'self' 'unsafe-inline'` だが、`/admin` は
**nonce ベースで `'unsafe-inline'` を持たない**。SSR された HTML に
`style="…"` を吐く実装は管理画面で落ちうる。

**公開ページと管理画面で共用するコンポーネントにインラインスタイルを持ち込むときは、
実際に `/admin` を開いて確認する。** Tailwind のクラスで済ませていれば問題ない。

## 表示とアクセシビリティ

規約の正本は `docs/coding-guidelines.md`「表示とアクセシビリティ」。要点だけ再掲する。

- **色だけで意味を伝えない。文言でも伝える**（NFR-08）
- タップ対象は 44px 以上。リンクとボタンに `min-h-11`（NFR-06）
- コントラスト比 4.5:1 以上
- **幅 360px で横スクロールを出さない**
- 値が無い項目は**欄ごと出さない**。空欄を並べない。出演時刻が無いときは「時刻未定」
- 日時には `<time dateTime={iso}>` を付ける
- **公開ページを新しく足したら `Disclaimer` を置く**（LR-01：全ページに非公式である旨）

## 依存パッケージ

**承認済みは以下のみ。足したくなったら実装せずに報告する。**

| 状態 | パッケージ |
| --- | --- |
| 使用中 | `next` / `react` / `react-dom` / `tailwindcss` v4 |
| 承認済み | （現時点でなし） |

いま進めている UI 刷新は**依存を足さない層から着手すると決めている**。
配色・余白・角丸・フォントスタック・チップ表示を `app/globals.css` の
Tailwind v4 `@theme` と既存コンポーネントの範囲で直す。

`shadcn/ui` / `lucide-react` / `motion` は候補として検討済みだが**まだ承認していない**。
必要になった時点で、何がどう足りないかを添えて相談すること。

## 触らないもの

- `lib/security-headers.ts` / `proxy.ts` / `next.config.ts` — CSP と認証の境界
- `lib/session.ts` — 管理者セッション
- `lib/calendar-range.ts` — 受け付ける年月の範囲。無制限にすると
  キャッシュミスを作られて DB に到達する（[ADR-0014](../docs/adr/0014-bounded-calendar-range.md)）
- `lib/*.test.ts` の既存の表明 — 規約を固定しているものがある

## 検証

```bash
npm run lint
npm run build
npm test          # node --test。lib/ のユニットテスト
```

**見た目を変えたら、幅 360px と、ライト / ダークの両方を実際に確認する。**
`prefers-color-scheme` で切り替わるため、片方だけ見て済ませると
もう片方でコントラストが落ちる。
