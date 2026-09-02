import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";
import { buildCsp, createNonce } from "@/lib/security-headers";
import { clientIpOf, createRateLimiter } from "@/lib/rate-limit";

/**
 * サイト全体の停止（LR-05 / ADR-0013）、管理画面への未認証アクセスの誘導、
 * そして CSP の付与（NFR-03）。
 *
 * Next.js 16 で middleware.ts は非推奨になり proxy.ts に改称された。
 * 役割は同じで、**ルートのレンダリングより前**に実行される。
 *
 * 公開カレンダーは ISR でキャッシュされるため、バックエンドを止めても
 * キャッシュ済みのページは配信され続ける。proxy は**キャッシュの手前**で
 * 全リクエストを受けるので、ここでしか確実に止められない。
 *
 * /admin を停止の対象外にしているのは、停止中も管理者が個別削除（FR-23）を
 * 行えるようにするため。
 */
/**
 * 公開ページのレート制限（NFR-03 / docs/security.md T-04）。
 *
 * **実クライアントの IP で絞れるのはここだけ。** Spring Boot から見た送信元は
 * Vercel の egress IP であり、そこで IP 単位に絞ると攻撃者ではなく
 * 全閲覧者がまとめて絞られる（docs/architecture.md 第 5.5 節）。
 *
 * 1 分 60 回。通常の閲覧は 1 分に数回で、next/link のプリフェッチを
 * 数えても届かない。値の根拠は docs/security.md 第 4.2 節。
 */
const PUBLIC_LIMIT = 60;
const PUBLIC_WINDOW_MS = 60_000;
const publicLimiter = createRateLimiter({
  limit: PUBLIC_LIMIT,
  windowMs: PUBLIC_WINDOW_MS,
});

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isDev = process.env.NODE_ENV === "development";
  const isAdmin = pathname.startsWith("/admin");

  // 管理画面は対象外。あちらはログイン試行の制限が守る（lib/login-rate-limit.ts）。
  // 認証済みの管理者を回数で締め出すと、訂正作業の途中で止まる
  if (!isAdmin && !publicLimiter.hit(clientIpOf(request.headers))) {
    return tooManyRequests();
  }

  if (
    process.env.SITE_DISABLED === "true" &&
    !isAdmin &&
    !pathname.startsWith("/unavailable")
  ) {
    return withCsp(NextResponse.rewrite(new URL("/unavailable", request.url)), buildCsp({ isDev }));
  }

  // 管理画面は未認証ならログインへ送る（FR-20）。
  // **これは UX のための誘導であって、認可そのものではない。**
  // Cookie の有無しか見ていないため、実際の検証は各ページと Server Action が
  // requireAdmin() で行う（lib/admin-api.ts）。ここだけに頼らない。
  if (isAdmin && !pathname.startsWith("/admin/login")) {
    if (!request.cookies.has(SESSION_COOKIE)) {
      return NextResponse.redirect(new URL("/admin/login", request.url));
    }
  }

  if (isAdmin) {
    return adminResponse(request, isDev);
  }

  // 公開ページは nonce を使わない。nonce はページを動的レンダリングにし、
  // **ISR を無効化する**（lib/security-headers.ts）。ISR は T-04 の主防御であり、
  // script-src を締めるためにそれを捨てるのは割に合わない
  return withCsp(NextResponse.next(), buildCsp({ isDev }));
}

/**
 * 管理画面には nonce ベースの厳格な CSP を当てる。
 *
 * <p>ISR を失わない。/admin は Cookie を読むため、もともと動的レンダリング。
 *
 * <p>nonce は**リクエストヘッダにも載せる**。Next.js はレンダリング時に
 * リクエスト側の CSP ヘッダを解析して nonce を取り出し、
 * 自身が出力する script / style に自動で付ける。
 */
function adminResponse(request: NextRequest, isDev: boolean) {
  const nonce = createNonce();
  const csp = buildCsp({ nonce, isDev });

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("content-security-policy", csp);

  return withCsp(NextResponse.next({ request: { headers: requestHeaders } }), csp);
}

/**
 * 超過時の応答。
 *
 * <p><b>本文を最小にする。</b> ここはレンダリングを避けるための経路であり、
 * 手の込んだページを返すと弾く意味が薄れる。
 *
 * <p>Retry-After を付けて、正規のクライアントが待つべき時間を伝える。
 */
function tooManyRequests(): NextResponse {
  return new NextResponse("リクエストが多すぎます。しばらく待ってからお試しください。", {
    status: 429,
    headers: {
      "Retry-After": String(PUBLIC_WINDOW_MS / 1000),
      "Content-Type": "text/plain; charset=utf-8",
      // 429 をキャッシュさせない。他の閲覧者にまで配られる
      "Cache-Control": "no-store",
    },
  });
}

function withCsp(response: NextResponse, csp: string): NextResponse {
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  // 静的アセットと Next.js の内部パスは対象外にする。
  // それらへの nosniff などは next.config.ts が付ける
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
