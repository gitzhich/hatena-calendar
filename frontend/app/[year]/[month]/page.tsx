import { notFound } from "next/navigation";
import { CalendarPage } from "@/components/CalendarPage";
import { parseMonthParams } from "@/lib/calendar-range";

export const revalidate = 300;

/**
 * **空配列を返すが、消してはいけない。**
 *
 * generateStaticParams が無いと Next.js はこのルートを純粋な動的レンダリング
 * として扱い、応答が no-store になって<b>毎リクエストが DB まで到達する</b>。
 * それでは T-04（無料枠の枯渇による可用性攻撃）の対策が成立しない
 * （docs/security.md T-04 / ADR-0014）。
 *
 * 空配列にするのは、ビルド時に 1 ページも事前生成しないため。
 * 全月を生成するとビルドがバックエンドの生死に依存する（ADR-0014 の却下案）。
 * この形なら「ビルド時は作らないが、初回アクセス時に生成して ISR で持つ」
 * になり、両方の要求を満たす。
 */
export function generateStaticParams() {
  return [];
}

/**
 * 指定月（FR-05：URL に年月を反映し、共有とリロードで同じ画面になる）。
 *
 * **範囲外はデータを取得する前に 404 にする**（ADR-0014）。
 * 年月ごとにキャッシュが分かれるため、範囲を絞らないと
 * キャッシュミスを無制限に作られ、そのすべてが DB へ到達する
 * （docs/security.md T-04）。ここで落とせば DB クエリは発生しない。
 */
export default async function MonthPage({
  params,
}: {
  params: Promise<{ year: string; month: string }>;
}) {
  const { year, month } = await params;
  const parsed = parseMonthParams(year, month);
  if (parsed === null) notFound();

  return <CalendarPage year={parsed.year} month={parsed.month} />;
}
