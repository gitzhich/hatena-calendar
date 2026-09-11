import { requireAdmin } from "@/lib/admin-api";
import { createAppearanceAction, currentCsrf } from "@/app/admin/actions";
import { ActionForm, Field } from "@/components/admin/FormFields";
import { todayInJst } from "@/lib/calendar-range";
import { toIsoDate } from "@/lib/iso-date";

/** 手動登録（FR-21）。 */
export default async function NewAppearancePage({
  searchParams,
}: {
  searchParams: Promise<{ sourceUrl?: string; ingestedPostId?: string }>;
}) {
  await requireAdmin();
  const csrf = await currentCsrf();
  // 未処理投稿から来た場合、出典 URL を引き継ぐ（FR-25）
  const params = await searchParams;

  return (
    <main>
      <h1 className="text-lg font-bold mb-4">出演情報の手動登録</h1>
      <ActionForm action={createAppearanceAction} csrf={csrf} submitLabel="登録する">
        {params.ingestedPostId && (
          <input type="hidden" name="ingestedPostId" value={params.ingestedPostId} />
        )}
        <Field
          label="開催日"
          name="appearanceDate"
          type="date"
          required
          max="9999-12-31"
          defaultValue={toIsoDate(todayInJst())}
        />
        <Field label="イベント名" name="eventName" required
               placeholder="『ORANGE CHEER』" />
        <Field label="会場" name="venueName" placeholder="愛知・大須RADHALL" />
        {/* 会場が未定のときだけ入れる（ADR-0022「会場が未定でも地域は持つ」） */}
        <Field label="地名（会場未定のとき）" name="areaName" placeholder="東京" />
        <Field label="出演 開始" name="performanceStartTime" type="time" />
        <Field label="出演 終了" name="performanceEndTime" type="time" />
        <Field label="物販 開始" name="merchStartTime" type="time" />
        <Field label="物販 終了" name="merchEndTime" type="time" />
        <Field label="チケット URL" name="ticketUrl" type="url" />
        <Field label="出典の投稿 URL" name="sourceUrl" type="url" required
               defaultValue={params.sourceUrl}
               placeholder="https://x.com/.../status/..." />
        <p className="text-xs text-neutral-600 dark:text-neutral-400">
          出典 URL は必須です。根拠のないデータを公開しません。
        </p>
      </ActionForm>
    </main>
  );
}
