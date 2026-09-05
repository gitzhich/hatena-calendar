import { JST_OFFSET_MINUTES } from "./calendar-range.ts";

/**
 * バックエンドが返す UTC の日時を JST の "YYYY/MM/DD HH:mm" にする（FR-08）。
 *
 * **toLocaleString を使わない。** あれは実行環境のタイムゾーン設定と ICU データに
 * 依存する。Vercel の Node は UTC で動くため timeZone を明示すれば足りるが、
 * ICU が同梱されない構成では指定が黙って無視されて 9 時間ずれる。
 * 失敗しても例外にならず、**ずれた時刻がそのまま表示される**のが厄介なところ。
 * オフセットを足して UTC のゲッターで読む方式なら、どの環境でも同じ値になる
 * （todayInJst と同じ方針。docs/data-model.md「タイムゾーンの扱い」）。
 *
 * パースできない値は null を返す。表示側はその場合に行ごと出さない。
 */
export function formatJst(iso: string): string | null {
  const epoch = Date.parse(iso);
  if (Number.isNaN(epoch)) return null;

  const jst = new Date(epoch + JST_OFFSET_MINUTES * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${jst.getUTCFullYear()}/${pad(jst.getUTCMonth() + 1)}/${pad(jst.getUTCDate())}` +
    ` ${pad(jst.getUTCHours())}:${pad(jst.getUTCMinutes())}`
  );
}
