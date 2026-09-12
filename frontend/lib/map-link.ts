/**
 * 会場の Google マップ URL を組み立てる（FR-09 / ADR-0022）。
 *
 * URL の書式は表示側の都合なので API は返さない
 * （docs/api.md「期間内の出演情報一覧」）。コンポーネントに直書きしない。
 */
export type MapLink = { href: string; confirmed: boolean };

const MAPS_SEARCH = "https://www.google.com/maps/search/?api=1";

export function mapLink(
  venueName: string | null,
  placeId: string | null,
): MapLink | null {
  if (venueName === null || venueName.trim() === "") {
    return null;
  }

  const query = `query=${encodeURIComponent(venueName)}`;
  const place =
    placeId === null ? "" : `&query_place_id=${encodeURIComponent(placeId)}`;
  return {
    href: `${MAPS_SEARCH}&${query}${place}`,
    confirmed: placeId !== null,
  };
}
