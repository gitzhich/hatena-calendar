import { notFound } from "next/navigation";
import { getAppearance, requireAdmin } from "@/lib/admin-api";
import {
  currentCsrf,
  deleteAppearanceAction,
  updateAppearanceAction,
} from "@/app/admin/actions";
import { ActionForm, Field } from "@/components/admin/FormFields";

/** 編集と削除（FR-22 / FR-23）。 */
export default async function EditAppearancePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireAdmin();
  const { id } = await params;
  if (!/^\d+$/.test(id)) notFound();

  const appearance = await getAppearance(Number(id));
  if (!appearance) notFound();

  const csrf = await currentCsrf();
  const hhmm = (t: string | null) => (t ? t.slice(0, 5) : null);

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">出演情報の編集</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        照合キー <code>{appearance.eventKey}</code>・
        {appearance.sourceType === "AUTO" ? "自動登録" : "手動登録"}
      </p>

      <ActionForm action={updateAppearanceAction} csrf={csrf} submitLabel="保存する">
        <input type="hidden" name="id" value={appearance.id} />
        {/*
          ingestedPostId は送らない。編集では変更できない導出値で、サーバが無視する
          （docs/api.md「編集」）。送ると「変えられる」と読める
        */}
        <Field label="開催日" name="appearanceDate" type="date" required
               defaultValue={appearance.appearanceDate} />
        <Field label="イベント名" name="eventName" required
               defaultValue={appearance.eventName} />
        <Field label="会場" name="venueName" defaultValue={appearance.venueName} />
        <Field label="出演 開始" name="performanceStartTime" type="time"
               defaultValue={hhmm(appearance.performanceStartTime)} />
        <Field label="出演 終了" name="performanceEndTime" type="time"
               defaultValue={hhmm(appearance.performanceEndTime)} />
        <Field label="物販 開始" name="merchStartTime" type="time"
               defaultValue={hhmm(appearance.merchStartTime)} />
        <Field label="物販 終了" name="merchEndTime" type="time"
               defaultValue={hhmm(appearance.merchEndTime)} />
        <Field label="チケット URL" name="ticketUrl" type="url"
               defaultValue={appearance.ticketUrl} />
        <Field label="出典の投稿 URL" name="sourceUrl" type="url" required
               defaultValue={appearance.sourceUrl} />
      </ActionForm>

      <hr className="my-8 border-neutral-300 dark:border-neutral-700" />

      <h2 className="text-sm font-bold mb-2">削除</h2>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-3">
        削除すると公開画面から即座に見えなくなります。
        取り込み済み投稿の記録は残るため、同じ投稿から再登録されることはありません。
      </p>
      <ActionForm action={deleteAppearanceAction} csrf={csrf} submitLabel="削除する" danger>
        <input type="hidden" name="id" value={appearance.id} />
      </ActionForm>
    </main>
  );
}
