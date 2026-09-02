import Link from "next/link";
import { fetchAppearances, fetchStatus } from "@/lib/api";
import { monthBounds } from "@/lib/calendar-range";
import { Calendar } from "@/components/Calendar";
import { AppearanceList } from "@/components/AppearanceList";
import { Disclaimer } from "@/components/Disclaimer";
import { LastUpdated } from "@/components/LastUpdated";

/** 当月ページと指定月ページで共有する本体。 */
export async function CalendarPage({ year, month }: { year: number; month: number }) {
  const { from, to } = monthBounds(year, month);
  // 1 か月分を 1 リクエストで取る。日付ごとに分割しない（NFR-01）。
  // 鮮度の取得は並行に走らせる。直列にすると再生成のたびに往復が 1 回増える
  const [result, status] = await Promise.all([fetchAppearances(from, to), fetchStatus()]);
  const appearances = result.ok ? result.appearances : [];

  return (
    <main className="mx-auto max-w-2xl p-4">
      <header className="mb-4">
        <h1 className="text-xl font-bold">
          <Link href="/">XINXIN 出演カレンダー</Link>
        </h1>
        {/* スクロールせずに免責へ到達できる導線（FR-07） */}
        <a href="#disclaimer" className="text-xs underline">
          このサイトについて（非公式）
        </a>
      </header>

      {!result.ok && (
        <p
          role="status"
          className="mb-4 rounded border border-amber-500 bg-amber-50 dark:bg-amber-950 p-3 text-sm"
        >
          出演情報を取得できませんでした。
          <strong>この月に予定がないという意味ではありません。</strong>
          時間をおいて再度お試しいただくか、公式 X をご確認ください。
        </p>
      )}

      <Calendar year={year} month={month} appearances={appearances} />

      <h2 className="mt-8 mb-3 text-base font-bold">出演一覧</h2>
      {result.ok && <AppearanceList appearances={appearances} />}

      {status.ok && <LastUpdated status={status.status} />}

      <Disclaimer />
    </main>
  );
}
