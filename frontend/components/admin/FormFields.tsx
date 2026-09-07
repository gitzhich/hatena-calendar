"use client";

import { useActionState } from "react";
import type { ActionState } from "@/app/admin/actions";

/** 操作の結果メッセージ。エラーだけを見せ、成功時は遷移する。 */
export function ActionForm({
  action,
  csrf,
  children,
  submitLabel,
  danger = false,
}: {
  action: (prev: ActionState, formData: FormData) => Promise<ActionState>;
  csrf: string;
  children?: React.ReactNode;
  submitLabel: string;
  danger?: boolean;
}) {
  const [state, formAction, pending] = useActionState(action, null);
  return (
    <form action={formAction} className="space-y-4">
      <input type="hidden" name="csrf" value={csrf} />
      {children}
      {state?.message && (
        <p role="alert" className="rounded border border-red-500 bg-red-50 dark:bg-red-950 p-2 text-sm">
          {state.message}
        </p>
      )}
      <button
        type="submit"
        disabled={pending}
        onClick={danger ? (e) => {
          // 誤操作防止のため確認を挟む（FR-23）
          if (!confirm("削除します。よろしいですか？")) e.preventDefault();
        } : undefined}
        className={`min-h-11 px-4 rounded text-sm font-medium disabled:opacity-50 ${
          danger
            ? "bg-red-700 text-white"
            : "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
        }`}
      >
        {pending ? "処理中…" : submitLabel}
      </button>
    </form>
  );
}

export function Field({
  label,
  name,
  type = "text",
  defaultValue,
  required = false,
  placeholder,
  min,
  max,
}: {
  label: string;
  name: string;
  type?: string;
  defaultValue?: string | null;
  required?: boolean;
  placeholder?: string;
  min?: string;
  max?: string;
}) {
  return (
    <label className="block">
      <span className="text-sm font-medium">
        {label}
        {required && <span className="text-red-600 ml-1">*</span>}
      </span>
      <input
        type={type}
        name={name}
        defaultValue={defaultValue ?? ""}
        required={required}
        placeholder={placeholder}
        min={min}
        max={max}
        // フロント側の検証は UX のためのもの。正はサーバ側（NFR-03）
        className="mt-1 w-full min-h-11 rounded border border-neutral-400 dark:border-neutral-600 bg-white dark:bg-neutral-900 px-2"
      />
    </label>
  );
}
