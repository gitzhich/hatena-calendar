import Link from "next/link";
import { listUnparsedPosts, requireAdmin } from "@/lib/admin-api";
import { currentCsrf, excludePostAction } from "@/app/admin/actions";
import { ActionForm } from "@/components/admin/FormFields";
import { Pager } from "@/components/admin/Pager";
import { formatJst } from "@/lib/last-updated";

/**
 * 未処理投稿（FR-25）。
 *
 * <b>投稿本文は保持していない</b>（LR-02）。原文は X 上で読む。
 */
export default async function UnparsedPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  await requireAdmin();
  const page = Number((await searchParams).page ?? "0") || 0;
  const result = await listUnparsedPosts(page);
  const csrf = await currentCsrf();

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">未処理の投稿</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        自動抽出できなかった投稿です。本文は保持していないため、
        原文は X で確認してください。
      </p>

      {result.items.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          未処理の投稿はありません。
        </p>
      ) : (
        <ul className="space-y-3">
          {result.items.map((post) => (
            <li
              key={post.id}
              className="rounded border border-neutral-300 dark:border-neutral-700 p-3"
            >
              {/* バックエンドは UTC で返す。JST への変換は表示側の責務
                  （docs/data-model.md「タイムゾーンの扱い」）。formatJst を使う理由は lib/last-updated.ts */}
              <p className="text-xs tabular-nums text-neutral-600 dark:text-neutral-400">
                投稿{" "}
                <time dateTime={post.postedAt}>{formatJst(post.postedAt) ?? "不明"}</time>
              </p>
              <p className="mt-2 flex flex-wrap items-center gap-4 text-xs">
                <a
                  href={post.postUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="underline"
                >
                  X で原文を読む
                </a>
                {/* 出典 URL を引き継いで登録画面へ（FR-25） */}
                <Link
                  href={`/admin/appearances/new?sourceUrl=${encodeURIComponent(post.postUrl)}&ingestedPostId=${post.id}`}
                  className="underline"
                >
                  出演情報として登録
                </Link>
              </p>
              <div className="mt-3">
                <ActionForm
                  action={excludePostAction}
                  csrf={csrf}
                  submitLabel="出演告知ではない"
                >
                  <input type="hidden" name="id" value={post.id} />
                </ActionForm>
              </div>
            </li>
          ))}
        </ul>
      )}

      <Pager
        page={result.page}
        size={result.size}
        total={result.totalElements}
        href={(p) => `/admin/unparsed?page=${p}`}
      />
    </main>
  );
}
