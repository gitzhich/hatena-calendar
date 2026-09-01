import "server-only";

/**
 * Spring Boot の公開 API を呼ぶ。
 *
 * **Server Component からのサーバ間通信でのみ使う**（docs/architecture.md 第 5.3 節）。
 * 内部 API キーがブラウザに渡らないよう "server-only" を付け、
 * クライアントバンドルへの混入をビルド時に落とす。
 */
export type Appearance = {
  id: number;
  appearanceDate: string;
  eventName: string;
  venueName: string | null;
  performanceStartTime: string | null;
  performanceEndTime: string | null;
  merchStartTime: string | null;
  merchEndTime: string | null;
  ticketUrl: string | null;
  sourceUrl: string;
};

/**
 * 「予定なし」と「取得できなかった」を区別する。
 *
 * 失敗時に空配列を返すと、バックエンドが落ちているだけなのに
 * 「その月は出演がない」と表示されてしまう。閲覧者にとっては誤情報になる。
 */
export type FetchResult =
  | { ok: true; appearances: Appearance[] }
  | { ok: false };

const BASE_URL = process.env.BACKEND_BASE_URL ?? "http://localhost:8080";

export async function fetchAppearances(from: string, to: string): Promise<FetchResult> {
  const url = `${BASE_URL}/api/public/appearances?from=${from}&to=${to}`;
  try {
    const res = await fetch(url, {
      headers: { "X-Api-Key": process.env.BACKEND_API_KEY ?? "" },
      // ISR。docs/architecture.md 第 5.2 節
      next: { revalidate: 300 },
    });
    if (!res.ok) {
      console.error(`公開 API が ${res.status} を返した`);
      return { ok: false };
    }
    const body = (await res.json()) as { appearances: Appearance[] };
    return { ok: true, appearances: body.appearances };
  } catch (e) {
    // バックエンドや DB の不調を公開画面のエラーとして表面化させない（NFR-02）。
    // ビルド時のプリレンダリングもここを通るため、
    // **バックエンドが落ちていてもビルドは通る**。
    console.error("公開 API に到達できなかった", e);
    return { ok: false };
  }
}
