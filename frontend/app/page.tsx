import { CalendarPage } from "@/components/CalendarPage";
import { todayInJst } from "@/lib/calendar-range";

// ISR（docs/architecture.md 第 5.2 節）。
// 期限切れ後もキャッシュ済みのページが即返り、再生成は裏で走るため
// 閲覧者は Neon のコールドスタートを待たない（NFR-01）。
export const revalidate = 300;

/** トップは当月（JST 基準）を表示する（FR-01）。 */
export default async function Home() {
  const { year, month } = todayInJst();
  return <CalendarPage year={year} month={month} />;
}
