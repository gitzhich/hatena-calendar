import Link from "next/link";
import { logout } from "@/app/admin/actions";

// 管理画面はキャッシュしない（docs/architecture.md 第 5.2 節）
export const dynamic = "force-dynamic";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto max-w-3xl p-4">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-2 border-b border-neutral-300 dark:border-neutral-700 pb-3">
        <nav className="flex flex-wrap items-center gap-4 text-sm">
          <Link href="/admin" className="font-bold">
            管理
          </Link>
          <Link href="/admin/appearances/new" className="underline">
            手動登録
          </Link>
          <Link href="/admin/unparsed" className="underline">
            未処理投稿
          </Link>
          <Link href="/" className="underline">
            公開ページ
          </Link>
        </nav>
        <form action={logout}>
          <button type="submit" className="min-h-11 text-sm underline">
            ログアウト
          </button>
        </form>
      </header>
      {children}
    </div>
  );
}
