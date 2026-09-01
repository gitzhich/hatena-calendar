"use client";

import { useActionState } from "react";
import { login, type ActionState } from "@/app/admin/actions";

/**
 * ログイン（FR-20）。
 *
 * <b>失敗理由を区別できるメッセージを返さない。</b> 単一管理者で
 * ユーザー名の概念がないため、成否だけを伝える。
 */
export default function LoginPage() {
  const [state, formAction, pending] = useActionState<ActionState, FormData>(login, null);

  return (
    <main className="mx-auto max-w-sm p-6">
      <h1 className="text-lg font-bold mb-4">管理者ログイン</h1>
      <form action={formAction} className="space-y-4">
        <label className="block">
          <span className="text-sm font-medium">パスワード</span>
          <input
            type="password"
            name="password"
            required
            autoComplete="current-password"
            className="mt-1 w-full min-h-11 rounded border border-neutral-400 dark:border-neutral-600 bg-white dark:bg-neutral-900 px-2"
          />
        </label>
        {state?.message && (
          <p role="alert" className="rounded border border-red-500 bg-red-50 dark:bg-red-950 p-2 text-sm">
            {state.message}
          </p>
        )}
        <button
          type="submit"
          disabled={pending}
          className="min-h-11 w-full rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 text-sm font-medium disabled:opacity-50"
        >
          {pending ? "確認中…" : "ログイン"}
        </button>
      </form>
    </main>
  );
}
