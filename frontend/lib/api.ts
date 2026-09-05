import "server-only";
import { backendBaseUrl } from "./backend-url";

/**
 * Spring Boot の公開 API を呼ぶ。
 *
 * **Server Component からのサーバ間通信でのみ使う**（docs/architecture.md「データ取得の方向」）。
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

const BASE_URL = backendBaseUrl();

export async function fetchAppearances(from: string, to: string): Promise<FetchResult> {
  const url = `${BASE_URL}/api/public/appearances?from=${from}&to=${to}`;
  try {
    const res = await fetch(url, {
      headers: { "X-Api-Key": process.env.BACKEND_API_KEY ?? "" },
      // ISR。docs/architecture.md「キャッシュ戦略」
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

/**
 * データの鮮度（FR-08 / docs/api.md「データの状態」）。
 *
 * `stale` の判定はバックエンドが持つ。基準（24 時間）をこちらに複製すると、
 * 片方だけ変えたときに食い違っても誰も気づけない。
 */
export type SiteStatus = {
  /** 最後に取り込みが成功した日時（UTC）。一度も成功していなければ null */
  lastSuccessfulIngestionAt: string | null;
  stale: boolean;
};

export type StatusResult = { ok: true; status: SiteStatus } | { ok: false };

/**
 * 取得できなければ表示を省く。
 *
 * 鮮度が分からないことを「新しい」とも「古い」とも言わない。
 * 誤った鮮度を出すくらいなら何も出さないほうがよい（FR-04 と同じ考え方）。
 */
export async function fetchStatus(): Promise<StatusResult> {
  try {
    const res = await fetch(`${BASE_URL}/api/public/status`, {
      headers: { "X-Api-Key": process.env.BACKEND_API_KEY ?? "" },
      // カレンダー本体と同じ ISR に載せる（docs/architecture.md「キャッシュ戦略」）。
      // 別のキャッシュにすると「日時だけ新しくてカレンダーは古い」という
      // 食い違いが起き、鮮度表示そのものが信用できなくなる
      next: { revalidate: 300 },
    });
    if (!res.ok) {
      console.error(`状態 API が ${res.status} を返した`);
      return { ok: false };
    }
    return { ok: true, status: (await res.json()) as SiteStatus };
  } catch (e) {
    console.error("状態 API に到達できなかった", e);
    return { ok: false };
  }
}
