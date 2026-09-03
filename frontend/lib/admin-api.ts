import "server-only";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE, openSession, type Session } from "@/lib/session";
import { backendBaseUrl } from "./backend-url";

/**
 * 管理 API のクライアント。
 *
 * **管理キーは、管理者セッションの検証に成功したときだけ読み込む**
 * （ADR-0010 / docs/architecture.md 第 3.1 節）。公開ページの
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
    // 管理画面はキャッシュしない（docs/architecture.md 第 5.2 節）
    cache: "no-store",
  });
}

export type AdminAppearance = {
  id: number;
  appearanceDate: string;
  eventName: string;
  eventKey: string;
  venueName: string | null;
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

/** 取り込み実行 1 回分（docs/api.md 第 5.7 節）。日時は UTC。 */
export type IngestionRun = {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  status: "RUNNING" | "SUCCESS" | "FAILED";
  fetchedResourceCount: number;
  newAppearanceCount: number;
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
  currentMonthResourceCount: number;
  consecutiveFailureCount: number;
  halted: boolean;
};

export async function listAppearances(
  sourceType?: string,
  page = 0,
): Promise<Paged<AdminAppearance>> {
  const query = new URLSearchParams({ page: String(page), size: "20" });
  if (sourceType) query.set("sourceType", sourceType);
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

/** ログインのためのパスワード検証（docs/api.md 第 6.1 節）。 */
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
