import Link from "next/link";
import { notFound } from "next/navigation";
import { getVenue, requireAdmin } from "@/lib/admin-api";
import { currentCsrf, resolveVenueAction, updateVenueAction } from "@/app/admin/actions";
import { ActionForm, Field, Select } from "@/components/admin/FormFields";
import { REGIONS, regionLabel } from "@/lib/region";
import { formatJst } from "@/lib/last-updated";

const REGION_OPTIONS = REGIONS.map((value) => ({ value, label: regionLabel(value) }));

/**
 * 会場の編集（FR-10 / ADR-0022）。
 *
 * **保存すると manuallyEdited が立ち、以後この行を自動処理が触らなくなる。**
 * 下ろす手段は無い。誤った地図リンクはリンクが無いより悪く、ファンが違う場所へ
 * 向かうため（docs/security.md T-08）、人が確認した値のほうを強くしている。
 */
export default async function EditVenuePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireAdmin();
  const { id } = await params;
  if (!/^\d+$/.test(id)) notFound();

  const venue = await getVenue(Number(id));
  if (!venue) notFound();

  const csrf = await currentCsrf();
  // 会場ではない行と、人が確認済みの行は自動解決の対象外（どちらも 409 になる）
  const canResolve = !venue.areaOnly && !venue.manuallyEdited;

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">会場の編集</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-1">
        照合キー <code>{venue.venueKey}</code>・出演{" "}
        <span className="tabular-nums">{venue.appearanceCount}</span> 件
      </p>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        地域を直すと、この会場の出演情報すべてに効きます。
        {venue.manuallyEdited
          ? "この会場は編集済みのため、自動判定と自動解決の対象外です。"
          : "保存すると、以後この会場を自動判定と自動解決が上書きしなくなります。"}
      </p>

      {venue.areaOnly && (
        <p className="mb-4 rounded border border-neutral-400 p-2 text-xs">
          これは会場ではなく地域だけの行です。会場が未定の出演情報が指しています。
          場所 ID は解決せず、地図リンクも出しません。
        </p>
      )}

      <ActionForm action={updateVenueAction} csrf={csrf} submitLabel="保存する">
        <input type="hidden" name="id" value={venue.id} />
        {/*
          venueKey は送らない。表記から機械的に決まる導出値で、変えると
          出演情報が別の会場に化ける（docs/api.md「会場の一覧と編集」）
        */}
        <Field label="表記" name="displayName" required defaultValue={venue.displayName} />
        <Select
          label="地域"
          name="region"
          required
          defaultValue={venue.region}
          options={REGION_OPTIONS}
        />

        {/*
          PUT は全項目の差し替え。placeId を送らないと null に戻り、解決済みの
          会場が未解決へ落ちる。閉じていても input は送信されるので、
          「触らずに保存」でも現在値がそのまま往復する
        */}
        <details className="rounded border border-neutral-300 dark:border-neutral-700 p-3">
          <summary className="min-h-11 flex items-center text-sm font-medium">
            場所 ID（{venue.placeId ? "解決済み" : "未解決"}）
          </summary>
          <p className="mt-2 text-xs text-neutral-600 dark:text-neutral-400">
            通常は触りません。自動解決が誤った場所を指しているときに空にして戻します。
            正しい ID の調べ方は docs/runbook-deploy.md「place_id を手で入れる」にあります。
          </p>
          {venue.placeIdCheckedAt && (
            <p className="mt-1 text-xs text-neutral-600 dark:text-neutral-400">
              最終試行{" "}
              <time dateTime={venue.placeIdCheckedAt}>
                {formatJst(venue.placeIdCheckedAt) ?? "不明"}
              </time>
            </p>
          )}
          <input
            type="text"
            name="placeId"
            defaultValue={venue.placeId ?? ""}
            className="mt-2 w-full min-h-11 rounded border border-neutral-400 dark:border-neutral-600 bg-white dark:bg-neutral-900 px-2"
          />
        </details>
      </ActionForm>

      {canResolve && (
        <>
          <hr className="my-8 border-neutral-300 dark:border-neutral-700" />
          <h2 className="text-sm font-bold mb-2">いま解決する</h2>
          <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-3">
            場所 ID は 1 日 1 回の定期実行が自動で埋めます。
            これは再試行の間隔（7 日）を待たずに 1 件だけ試すための操作です。
          </p>
          <ActionForm action={resolveVenueAction} csrf={csrf} submitLabel="いま解決する">
            <input type="hidden" name="id" value={venue.id} />
          </ActionForm>
        </>
      )}

      <p className="mt-8 text-xs">
        <Link href="/admin/venues" className="min-h-11 inline-flex items-center underline">
          ← 会場の一覧へ
        </Link>
      </p>
    </main>
  );
}
