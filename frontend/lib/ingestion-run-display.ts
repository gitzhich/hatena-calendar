/**
 * 取り込み実行 1 回分の表示（docs/api.md「取り込み履歴」）。
 */

/**
 * この実行で未処理にした件数の表示。
 *
 * `null` は「0 件」ではなく「分からない」（列を足す前の実行記録と、失敗した実行）。
 * フィールド自体が無い応答（`undefined`）も同じ。バックエンド未デプロイ時は返ってこない。
 */
export function unparsedCountLabel(count: number | null | undefined): string {
  if (count === null || count === undefined) return "—";
  return `${count} 件`;
}
