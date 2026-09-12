"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import {
  createAppearance,
  deleteAppearance,
  excludeUnparsedPost,
  requireAdmin,
  resolveVenuePlaceId,
  updateAppearance,
  updateVenue,
  verifyPassword,
} from "@/lib/admin-api";
import {
  allowLogin,
  recordLoginFailure,
  recordLoginSuccess,
} from "@/lib/login-rate-limit";
import {
  SESSION_COOKIE,
  SESSION_COOKIE_PATH,
  SESSION_TTL_SECONDS,
  csrfMatches,
  newSession,
  openSession,
  sealSession,
} from "@/lib/session";

export type ActionState = { message: string } | null;

/**
 * 管理操作の共通の入口。
 *
 * <b>CSRF トークンを検証する。</b> Server Actions は Origin ヘッダも検査するが、
 * docs/security.md T-02 は SameSite=Lax に加えてトークンを要求すると定めている。
 * どれか 1 つが漏れても他で止まる構成にする。
 */
async function guard(formData: FormData): Promise<void> {
  const session = await requireAdmin();
  if (!csrfMatches(session.csrf, formData.get("csrf"))) {
    throw new Error("不正なリクエストです");
  }
}

/** 管理操作の後は公開ページを再検証する（FR-22：保存後ただちに反映）。 */
function revalidatePublicPages(): void {
  revalidatePath("/", "layout");
}

// ------------------------------------------------------------------
// ログイン / ログアウト（FR-20）
// ------------------------------------------------------------------

export async function login(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const password = formData.get("password");
  if (typeof password !== "string" || password.length === 0) {
    return { message: "パスワードを入力してください" };
  }

  // 実クライアントの IP で絞る。Vercel が渡すヘッダを見る
  const requestHeaders = await headers();
  const clientIp =
    requestHeaders.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";

  if (!allowLogin(clientIp)) {
    return { message: "試行回数の上限に達しました。しばらく待ってからお試しください" };
  }

  if (!(await verifyPassword(password))) {
    recordLoginFailure(clientIp);
    // 失敗理由を区別できるメッセージを返さない（FR-20）
    return { message: "ログインできませんでした" };
  }

  recordLoginSuccess(clientIp);
  const store = await cookies();
  store.set(SESSION_COOKIE, await sealSession(newSession()), {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    // 公開ページには送らない（lib/session.ts の SESSION_COOKIE_PATH を参照）
    path: SESSION_COOKIE_PATH,
    maxAge: SESSION_TTL_SECONDS,
  });
  redirect("/admin");
}

export async function logout(): Promise<void> {
  const store = await cookies();
  // path を省くと「/」の Cookie を消しにいってしまい、
  // /admin に付いた Cookie が残る（set と同じ path を渡す）
  store.delete({ name: SESSION_COOKIE, path: SESSION_COOKIE_PATH });
  redirect("/admin/login");
}

// ------------------------------------------------------------------
// 出演情報（FR-21 / FR-22 / FR-23）
// ------------------------------------------------------------------

/** 空文字は null にする。「未入力」と「空文字」を区別する必要がある。 */
const orNull = (value: FormDataEntryValue | null): string | null => {
  const text = typeof value === "string" ? value.trim() : "";
  return text.length === 0 ? null : text;
};

const withSeconds = (value: string | null): string | null =>
  value === null ? null : value.length === 5 ? `${value}:00` : value;

function toPayload(formData: FormData) {
  return {
    appearanceDate: orNull(formData.get("appearanceDate")),
    eventName: orNull(formData.get("eventName")),
    venueName: orNull(formData.get("venueName")),
    areaName: orNull(formData.get("areaName")),
    performanceStartTime: withSeconds(orNull(formData.get("performanceStartTime"))),
    performanceEndTime: withSeconds(orNull(formData.get("performanceEndTime"))),
    merchStartTime: withSeconds(orNull(formData.get("merchStartTime"))),
    merchEndTime: withSeconds(orNull(formData.get("merchEndTime"))),
    ticketUrl: orNull(formData.get("ticketUrl")),
    sourceUrl: orNull(formData.get("sourceUrl")),
    // 効くのは登録のときだけ。編集ではサーバが無視する（docs/api.md「編集」）
    ingestedPostId: orNull(formData.get("ingestedPostId")) === null
      ? null
      : Number(formData.get("ingestedPostId")),
  };
}

export async function createAppearanceAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const result = await createAppearance(toPayload(formData));
  if (!result.ok) return { message: result.message };
  revalidatePublicPages();
  redirect("/admin");
}

export async function updateAppearanceAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const id = Number(formData.get("id"));
  const result = await updateAppearance(id, toPayload(formData));
  if (!result.ok) return { message: result.message };
  revalidatePublicPages();
  redirect("/admin");
}

export async function deleteAppearanceAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const result = await deleteAppearance(Number(formData.get("id")));
  if (!result.ok) return { message: result.message };
  revalidatePublicPages();
  redirect("/admin");
}

// ------------------------------------------------------------------
// 未処理投稿（FR-25）
// ------------------------------------------------------------------

export async function excludePostAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const result = await excludeUnparsedPost(Number(formData.get("id")));
  if (!result.ok) return { message: result.message };
  redirect("/admin/unparsed");
}

// ------------------------------------------------------------------
// 会場（FR-10 / ADR-0022）
// ------------------------------------------------------------------

/**
 * 会場の訂正。
 *
 * **`PUT` は全項目の差し替え。** `placeId` を送り忘れると `null` に戻り、
 * 解決済みの会場が静かに未解決へ落ちる。フォームは現在値を隠さず常に送る
 * （docs/api.md「会場の一覧と編集」）。
 *
 * **公開ページを再検証する。** `region` はカレンダーの色に直結し、公開ページは
 * ISR でキャッシュされている。呼ばないと古い色が残る（FR-22）。
 */
export async function updateVenueAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const id = Number(formData.get("id"));
  const result = await updateVenue(id, {
    displayName: orNull(formData.get("displayName")),
    region: orNull(formData.get("region")),
    placeId: orNull(formData.get("placeId")),
  });
  if (!result.ok) return { message: result.message };
  revalidatePublicPages();
  redirect("/admin/venues");
}

/**
 * 今すぐ 1 件だけ解決する。
 *
 * 結果（見つかったか、試行日時だけが進んだか）を見せたいので、
 * 一覧ではなく同じ会場の画面へ戻す。
 */
export async function resolveVenueAction(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  await guard(formData);
  const id = Number(formData.get("id"));
  const result = await resolveVenuePlaceId(id);
  if (!result.ok) return { message: result.message };
  // place_id は地図リンクに使う。公開ページのキャッシュを更新する
  revalidatePublicPages();
  redirect(`/admin/venues/${id}`);
}

/** 画面から CSRF トークンを取り出すための補助。 */
export async function currentCsrf(): Promise<string> {
  const store = await cookies();
  const session = await openSession(store.get(SESSION_COOKIE)?.value);
  return session?.csrf ?? "";
}
