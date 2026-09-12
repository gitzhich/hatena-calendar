/**
 * 会場の地域（ADR-0022）。
 *
 * **表示名はクライアントが決める。** API が返すのは識別子だけで、
 * どう見せるかはこちらの決定（docs/api.md「期間内の出演情報一覧」）。
 *
 * 並びはバックエンドの `Region` の宣言順（北から南 → 海外 → 不明）に揃える。
 * 一覧も同じ順で返るので、選択肢だけ別の順にすると読み手が突き合わせられない。
 */

export const REGIONS = [
  "HOKKAIDO",
  "TOHOKU",
  "KANTO",
  "CHUBU",
  "KINKI",
  "CHUGOKU",
  "SHIKOKU",
  "KYUSHU",
  "OVERSEAS",
  "UNKNOWN",
] as const;

export type Region = (typeof REGIONS)[number];

export const REGION_LABELS: Record<Region, string> = {
  HOKKAIDO: "北海道",
  TOHOKU: "東北",
  KANTO: "関東",
  CHUBU: "中部",
  KINKI: "近畿",
  CHUGOKU: "中国",
  SHIKOKU: "四国",
  KYUSHU: "九州",
  OVERSEAS: "海外",
  UNKNOWN: "不明",
};

/**
 * 知らない値はそのまま出す。
 *
 * バックエンドが地方を増やしたとき、フロントを直すまでの間は識別子が見える。
 * 空欄や「不明」に倒すと、**増えたことに気づけない**。
 */
export function regionLabel(region: string): string {
  return REGION_LABELS[region as Region] ?? region;
}
