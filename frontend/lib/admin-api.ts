import "server-only";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE, openSession, type Session } from "@/lib/session";
import { backendBaseUrl } from "./backend-url";
import { DEFAULT_APPEARANCE_SORT, type AppearanceSort } from "./admin-appearance-query";
import type { Region } from "./region";

/**
 * 管理 API のクライアント。
 *
 * **管理キーは、管理者セッションの検証に成功したときだけ読み込む**
 * （ADR-0010 / docs/architecture.md「経路ごとの保護」）。公開ページの
 * レンダリング経路からこのモジュールを呼ばない。
 *
 * キーを 1 種類にすると、公開ページの取得に使うキーが漏れただけで
 * 管理操作まで通ってしまう。分離の意味は、読み込む場所を絞ってこそ出る。
 */

const BASE_URL = backendBaseUrl();

/** 認証済みでなければログイン画面へ送る。管理画面の入口すべてで呼ぶ。 */
export async function requireAdmin(): Promise<Session> {
  const store = await cookies();
  const session = await openSession(store.get(SESSION_COOKIE)?.value);
  if (!session) redirect("/admin/login");
  return session;
}

async function adminFetch(path: string, init?: RequestInit): Promise<Response> {
  // 呼び出し前に必ず検証する。ここを通らない経路で管理キーを読ませない
  await requireAdmin();
  return fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-Admin-Api-Key": process.env.BACKEND_ADMIN_API_KEY ?? "",
      ...init?.headers,
    },
    // 管理画面はキャッシュしない（docs/architecture.md「キャッシュ戦略」）
    cache: "no-store",
  });
}

export type AdminAppearance = {
  id: number;
  appearanceDate: string;
  eventName: string;
  eventKey: string;
  venueName: string | null;
  /** 会場が未定のときの地名。地域はここから引かれる（ADR-0022） */
  areaName: string | null;
  performanceStartTime: string | null;
  performanceEndTime: string | null;
  merchStartTime: string | null;
  merchEndTime: string | null;
  ticketUrl: string | null;
  sourceUrl: string;
  sourceType: "AUTO" | "MANUAL";
  ingestedPostId: number | null;
  createdAt: string;
  updatedAt: string;
};

/**
 * 会場 1 行（docs/api.md「会場の一覧と編集」/ ADR-0022）。
 *
 * `venue` は 1 会場 1 行なので、**1 行直せば過去の全出演に効く**。
 * `appearanceCount` はその効き目の大きさ。
 */
export type AdminVenue = {
  id: number;
  /** 照合キー。表記から機械的に決まるので**編集できない** */
  venueKey: string;
  displayName: string;
  region: Region;
  /** `null` は「まだ同定できていない」。地図リンクは名前検索に落ちる */
  placeId: string | null;
  placeIdCheckedAt: string | null;
  /** `true` の行は自動判定・自動解決が触らない */
  manuallyEdited: boolean;
  /** 会場ではなく地域だけの行（`東京`）。place_id を解決しない */
  areaOnly: boolean;
  appearanceCount: number;
};

export type UnparsedPost = {
  id: number;
  tweetId: string;
  postUrl: string;
  postedAt: string;
  ingestedAt: string;
};

export type Paged<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
};

/** 取り込み実行 1 回分（docs/api.md「取り込み履歴」）。日時は UTC。 */
export type IngestionRun = {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  /**
   * `CANCELLED` は**管理者が原因を確認し、打ち切りカウントから外した失敗**
   * （docs/runbook-x-api-setup.md「打ち切りから戻す」）。アプリからは遷移させない。
   */
  status: "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLED";
  fetchedResourceCount: number;
  newAppearanceCount: number;
  /**
   * この実行で未処理にした投稿の件数（FR-25）。
   * `null` は「0 件」ではなく「分からない」——列を足す前の実行記録と、失敗した実行。
   * フィールド自体が無い応答（本番未デプロイ）も同じ扱いにする。
   */
  unparsedCount: number | null;
  /**
   * ページ上限で打ち切ったか。`true` なら**古い投稿を取りこぼしている**
   * （docs/x-integration.md「ページング」 / ADR-0020）。`status` は `SUCCESS` のまま。
   */
  truncated: boolean;
  errorSummary: string | null;
};

/**
 * 取り込み履歴と、そこから導かれる運用の指標。
 *
 * `halted` と `consecutiveFailureCount` は**バックエンドの判定をそのまま使う**。
 * 実際に取り込みを止める条件と画面に出す条件を 1 か所に集約するため、
 * ここで items を数え直さない（NFR-09 / `IngestionHaltRule`）。
 */
export type IngestionRunList = Paged<IngestionRun> & {
  /**
   * 現在の請求サイクルで取得したリソース数。**暦月ではない**（NFR-04）。
   * 区切りはバックエンドの `BillingCycle` が決める。
   */
  currentCycleResourceCount: number;
  /** 集計期間の開始（UTC）。何を合計した値かを画面に出すために使う。 */
  cycleStartAt: string;
  consecutiveFailureCount: number;
  halted: boolean;
};

export async function listAppearances(
  sourceType?: string,
  page = 0,
  sort?: AppearanceSort,
): Promise<Paged<AdminAppearance>> {
  const query = new URLSearchParams({ page: String(page), size: "20" });
  if (sourceType) query.set("sourceType", sourceType);
  if (sort && sort !== DEFAULT_APPEARANCE_SORT) query.set("sort", sort);
  const res = await adminFetch(`/api/admin/appearances?${query}`);
  if (!res.ok) throw new Error(`一覧の取得に失敗しました (${res.status})`);
  return res.json();
}

export async function getAppearance(id: number): Promise<AdminAppearance | null> {
  const res = await adminFetch(`/api/admin/appearances/${id}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`取得に失敗しました (${res.status})`);
  return res.json();
}

export async function listIngestionRuns(
  page = 0,
  size = 20,
): Promise<IngestionRunList> {
  const res = await adminFetch(`/api/admin/ingestion-runs?page=${page}&size=${size}`);
  if (!res.ok) throw new Error(`取り込み履歴の取得に失敗しました (${res.status})`);
  return res.json();
}

export async function listUnparsedPosts(page = 0): Promise<Paged<UnparsedPost>> {
  const res = await adminFetch(`/api/admin/unparsed-posts?page=${page}&size=20`);
  if (!res.ok) throw new Error(`一覧の取得に失敗しました (${res.status})`);
  return res.json();
}

export async function listVenues(
  unresolved: boolean,
  page = 0,
): Promise<Paged<AdminVenue>> {
  const query = new URLSearchParams({ page: String(page), size: "20" });
  if (unresolved) query.set("unresolved", "true");
  const res = await adminFetch(`/api/admin/venues?${query}`);
  if (!res.ok) throw new Error(`会場一覧の取得に失敗しました (${res.status})`);
  return res.json();
}

export async function getVenue(id: number): Promise<AdminVenue | null> {
  const res = await adminFetch(`/api/admin/venues/${id}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`取得に失敗しました (${res.status})`);
  return res.json();
}

/** 登録・編集・削除の結果。画面へ返すために失敗理由を保持する。 */
export type MutationResult = { ok: true; id?: number } | { ok: false; message: string };

async function mutate(
  path: string,
  method: string,
  body?: unknown,
): Promise<MutationResult> {
  const res = await adminFetch(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.ok) {
    if (res.status === 204) return { ok: true };
    const created = (await res.json()) as { id?: number };
    return { ok: true, id: created.id };
  }
  // バックエンドは RFC 7807 で返す。detail をそのまま見せる。
  // スタックトレースや内部構造は含まれない（NFR-03）
  const problem = (await res.json().catch(() => null)) as { detail?: string } | null;
  return {
    ok: false,
    message: problem?.detail ?? `操作に失敗しました (${res.status})`,
  };
}

export const createAppearance = (body: unknown) =>
  mutate("/api/admin/appearances", "POST", body);

export const updateAppearance = (id: number, body: unknown) =>
  mutate(`/api/admin/appearances/${id}`, "PUT", body);

export const deleteAppearance = (id: number) =>
  mutate(`/api/admin/appearances/${id}`, "DELETE");

export const excludeUnparsedPost = (id: number) =>
  mutate(`/api/admin/unparsed-posts/${id}/exclude`, "POST");

/**
 * 会場の訂正。
 *
 * **全項目の差し替え。** `placeId` を省くと `null` になり、解決済みの会場が
 * 未解決へ戻る（docs/api.md「会場の一覧と編集」）。呼ぶ側が常に現在値を渡す。
 */
export const updateVenue = (id: number, body: unknown) =>
  mutate(`/api/admin/venues/${id}`, "PUT", body);

/** 再試行の間隔（7 日）を待たずに 1 件だけ解決する。編集済み・地域だけの行は 409。 */
export const resolveVenuePlaceId = (id: number) =>
  mutate(`/api/admin/venues/${id}/resolve-place-id`, "POST");

/**
 * 会場の削除。
 *
 * **出演情報から参照されている会場は 409**（docs/api.md「会場の一覧と編集」）。
 * 画面はボタンを出さないが、判定の正はサーバ側（NFR-03）。
 */
export const deleteVenue = (id: number) =>
  mutate(`/api/admin/venues/${id}`, "DELETE");

/** ログインのためのパスワード検証（docs/api.md「管理者パスワードの検証」）。 */
export async function verifyPassword(password: string): Promise<boolean> {
  const res = await fetch(`${BASE_URL}/internal/auth`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Admin-Api-Key": process.env.BACKEND_ADMIN_API_KEY ?? "",
    },
    body: JSON.stringify({ password }),
    cache: "no-store",
  });
  if (!res.ok) return false;
  const body = (await res.json()) as { authenticated: boolean };
  return body.authenticated === true;
}
