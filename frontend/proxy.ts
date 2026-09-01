import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";

/**
 * サイト全体の停止（LR-05 / ADR-0013）と、管理画面への未認証アクセスの誘導。
 *
 * Next.js 16 で middleware.ts は非推奨になり proxy.ts に改称された。
 * 役割は同じで、**ルートのレンダリングより前**に実行される。
 *
 * 公開カレンダーは ISR でキャッシュされるため、バックエンドを止めても
 * キャッシュ済みのページは配信され続ける。middleware は**キャッシュの手前**で
 * 全リクエストを受けるので、ここでしか確実に止められない。
 *
 * /admin を停止の対象外にしているのは、停止中も管理者が個別削除（FR-23）を
 * 行えるようにするため。
 */
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (
    process.env.SITE_DISABLED === "true" &&
    !pathname.startsWith("/admin") &&
    !pathname.startsWith("/unavailable")
  ) {
    return NextResponse.rewrite(new URL("/unavailable", request.url));
  }

  // 管理画面は未認証ならログインへ送る（FR-20）。
  // **これは UX のための誘導であって、認可そのものではない。**
  // Cookie の有無しか見ていないため、実際の検証は各ページと Server Action が
  // requireAdmin() で行う（lib/admin-api.ts）。ここだけに頼らない。
  if (pathname.startsWith("/admin") && !pathname.startsWith("/admin/login")) {
    if (!request.cookies.has(SESSION_COOKIE)) {
      return NextResponse.redirect(new URL("/admin/login", request.url));
    }
  }

  return NextResponse.next();
}

export const config = {
  // 静的アセットと Next.js の内部パスは対象外にする
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
