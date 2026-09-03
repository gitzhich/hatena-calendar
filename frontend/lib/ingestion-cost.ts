/**
 * X API の消費リソース数から概算コストを出す（NFR-04 / docs/api.md 第 5.7 節）。
 *
 * **課金単位はレスポンスで返ってきたリソース数**であり、リクエスト数ではない
 * （CLAUDE.md の X API 連携）。バックエンドはその合計だけを返し、
 * 単価の換算はここで行う。
 */

/** Posts: Read の単価（USD / 1 リソース）。 */
export const USD_PER_RESOURCE = 0.005;

/** NFR-04 の想定上限。通常運用における月の消費額がこれを超えない。 */
export const MONTHLY_BUDGET_USD = 5;

/**
 * 概算コストを `$1.44` の形にする。
 *
 * 単価が 1 リソース 0.5 セントなので、**セントの整数に丸めてから割る**。
 * 浮動小数のまま toFixed に渡すと、値によって最後の桁が揺れる。
 */
export function estimateUsd(resources: number): string {
  if (!Number.isFinite(resources) || resources <= 0) return "$0.00";
  const cents = Math.round(resources * USD_PER_RESOURCE * 100);
  return `$${(cents / 100).toFixed(2)}`;
}

/**
 * 想定額を超えているか（NFR-04 の「想定を超えた場合に気づける」）。
 *
 * **ちょうど上限は超過にしない。** 要件は「月 $5 を超えない」であり、
 * $5.00 は満たしている。
 */
export function overBudget(resources: number): boolean {
  return resources * USD_PER_RESOURCE > MONTHLY_BUDGET_USD;
}
