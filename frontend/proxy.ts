import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";
import { buildCsp, createNonce } from "@/lib/security-headers";

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
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isDev = process.env.NODE_ENV === "development";
  const isAdmin = pathname.startsWith("/admin");

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

function withCsp(response: NextResponse, csp: string): NextResponse {
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  // 静的アセットと Next.js の内部パスは対象外にする。
  // それらへの nosniff などは next.config.ts が付ける
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
