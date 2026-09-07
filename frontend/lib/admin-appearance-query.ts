/**
 * 点検一覧のクエリ（docs/api.md「出演情報の一覧と個別取得（点検用）」）。
 *
 * 並び順と絞り込みの組み合わせを 1 か所で組み立てる。ページャと
 * 絞り込みのリンクが別々に書くと、片方だけ sort を落として既定に戻る。
 */

export const APPEARANCE_SORTS = [
  "DATE_DESC",
  "DATE_ASC",
  "CREATED_DESC",
  "CREATED_ASC",
] as const;

export type AppearanceSort = (typeof APPEARANCE_SORTS)[number];

/** API の既定。URL にも付けない（同じ画面に 2 通りの URL を作らない）。 */
export const DEFAULT_APPEARANCE_SORT: AppearanceSort = "DATE_DESC";

export const APPEARANCE_SORT_OPTIONS: ReadonlyArray<{
  value: AppearanceSort;
  label: string;
}> = [
  { value: "DATE_DESC", label: "公演が新しい順" },
  { value: "DATE_ASC", label: "公演が古い順" },
  { value: "CREATED_DESC", label: "登録が新しい順" },
  { value: "CREATED_ASC", label: "登録が古い順" },
];

export type AppearanceSourceType = "AUTO" | "MANUAL";

/** 知らない値は既定に倒す。API も 400 にせず既定へ倒す。 */
export function parseAppearanceSort(value: string | undefined): AppearanceSort {
  return APPEARANCE_SORTS.find((candidate) => candidate === value) ?? DEFAULT_APPEARANCE_SORT;
}

/**
 * 点検一覧のリンク。既定の sort は付けない。page を渡したときだけ page を付ける
 * （絞り込み・並び順の切り替えは 1 ページ目へ戻す）。
 */
export function adminAppearancesHref({
  sourceType,
  sort,
  page,
}: {
  sourceType?: AppearanceSourceType;
  sort: AppearanceSort;
  page?: number;
}): string {
  const query = new URLSearchParams();
  if (sourceType) query.set("sourceType", sourceType);
  if (sort !== DEFAULT_APPEARANCE_SORT) query.set("sort", sort);
  if (page !== undefined) query.set("page", String(page));
  const qs = query.toString();
  return qs.length === 0 ? "/admin" : `/admin?${qs}`;
}
