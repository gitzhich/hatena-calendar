import Link from "next/link";

/**
 * 一覧のページ送り。
 *
 * リンク先の組み立ては呼び出し側に任せる（一覧ごとに絞り込みの引数が違うため）。
 * タップ対象は 44px を確保する（NFR-06）。
 */
export function Pager({
  page,
  size,
  total,
  href,
}: {
  page: number;
  size: number;
  total: number;
  href: (page: number) => string;
}) {
  const last = Math.max(0, Math.ceil(total / size) - 1);
  return (
    <nav className="mt-6 flex items-center justify-between text-sm">
      {page > 0 ? (
        <Link href={href(page - 1)} className="min-h-11 inline-flex items-center underline">
          ← 前
        </Link>
      ) : (
        <span />
      )}
      <span className="text-xs tabular-nums">
        {total === 0 ? "0 件" : `${page + 1} / ${last + 1} ページ・全 ${total} 件`}
      </span>
      {page < last ? (
        <Link href={href(page + 1)} className="min-h-11 inline-flex items-center underline">
          次 →
        </Link>
      ) : (
        <span />
      )}
    </nav>
  );
}
