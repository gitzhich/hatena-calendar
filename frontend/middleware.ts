import { NextResponse, type NextRequest } from "next/server";

/**
 * サイト全体の停止（LR-05 / ADR-0013）。
 *
 * 公開カレンダーは ISR でキャッシュされるため、バックエンドを止めても
 * キャッシュ済みのページは配信され続ける。middleware は**キャッシュの手前**で
 * 全リクエストを受けるので、ここでしか確実に止められない。
 *
 * /admin を除外しているのは、停止中も管理者が個別削除（FR-23）を
 * 行えるようにするため。
 */
export function middleware(request: NextRequest) {
  if (process.env.SITE_DISABLED !== "true") return NextResponse.next();

  const { pathname } = request.nextUrl;
  if (pathname.startsWith("/admin") || pathname.startsWith("/unavailable")) {
    return NextResponse.next();
  }
  return NextResponse.rewrite(new URL("/unavailable", request.url));
}

export const config = {
  // 静的アセットと Next.js の内部パスは対象外にする
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
