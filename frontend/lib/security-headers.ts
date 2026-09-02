/**
 * セキュリティヘッダ（NFR-03 / docs/security.md 第 4 章）。
 *
 * **CSP は proxy.ts で 1 度だけ付ける。** next.config.ts と proxy.ts の
 * 両方から出すと `Content-Security-Policy` が 2 本になり、ブラウザは
 * **両方を同時に適用する**（許可の積集合）。片方を緩めたつもりが
 * もう片方で落ちる、という読めない挙動になるため一本化する。
 *
 * 常時付けるヘッダ（HSTS / nosniff / Referrer-Policy）は next.config.ts 側に置く。
 * あちらは静的アセットにも付くのが利点で、`nosniff` は JS / CSS にこそ効かせたい。
 */

/** 2 年。プリロードリストへの登録要件に合わせる。 */
export const HSTS = "max-age=63072000; includeSubDomains; preload";

/** 同一オリジンには完全な URL、外部へはオリジンのみ送る。 */
export const REFERRER_POLICY = "strict-origin-when-cross-origin";

export const NOSNIFF = "nosniff";

/**
 * CSP を組み立てる。
 *
 * <b>nonce の有無で 2 系統ある。</b> nonce を使うとページは動的レンダリングになり、
 * **ISR が無効化される**（Next.js の CSP ガイド）。ISR は T-04（無料枠の枯渇による
 * 可用性攻撃）の主防御なので、公開ページでは nonce を使わない。
 * 代わりに `/admin` だけ nonce を使う。あちらは Cookie を読むため
 * もともと動的レンダリングで、ISR を失わない。
 *
 * **公開ページで緩むのは `script-src` だけ。** clickjacking（frame-ancestors）、
 * XSS 後のデータ持ち出し（connect-src / img-src / form-action）、
 * 相対 URL の乗っ取り（base-uri）はいずれも同じ強さで効く。
 */
export function buildCsp(options: { nonce?: string; isDev?: boolean } = {}): string {
  const { nonce, isDev = false } = options;

  // 開発時の 'unsafe-eval' は React が eval でエラースタックを復元するため。
  // 本番では React も Next.js も eval を使わない
  const devEval = isDev ? " 'unsafe-eval'" : "";

  const scriptSrc = nonce
    ? `'self' 'nonce-${nonce}' 'strict-dynamic'${devEval}`
    : `'self' 'unsafe-inline'${devEval}`;

  // 開発時は Next.js が nonce の付かないスタイルを差し込むため緩める
  const styleSrc = nonce && !isDev ? `'self' 'nonce-${nonce}'` : "'self' 'unsafe-inline'";

  const directives = [
    "default-src 'self'",
    `script-src ${scriptSrc}`,
    `style-src ${styleSrc}`,
    // favicon などの data: を許す。外部ホストは一切読み込まない
    "img-src 'self' data:",
    // next/font を使っておらず、システムフォントで足りている
    "font-src 'self'",
    // XSS が成立しても外部へ送り出せないようにする
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    // clickjacking 対策。X-Frame-Options の後継で、こちらが優先される
    "frame-ancestors 'none'",
  ];

  // ローカルは HTTP で動かすため本番だけ。開発で付けると同一オリジンの
  // http リソースまで https へ上書きされ、開発サーバに繋がらなくなる
  if (!isDev) {
    directives.push("upgrade-insecure-requests");
  }

  return directives.join("; ");
}

/** nonce を 1 リクエストにつき 1 つ作る。推測できないことが前提の仕組み。 */
export function createNonce(): string {
  return crypto.randomUUID().replaceAll("-", "");
}
