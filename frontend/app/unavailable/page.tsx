import type { Metadata } from "next";

export const metadata: Metadata = { title: "公開を停止しています" };

/**
 * 停止中の案内（ADR-0013）。middleware が SITE_DISABLED のとき
 * ここへ rewrite する。
 *
 * 削除要請の連絡先を残す。要請者が状況を確認できないまま
 * サイトが消える状態にしない。連絡手段そのものは
 * docs/requirements.md 第 12 章の未決定事項。
 */
export default function Unavailable() {
  return (
    <main className="mx-auto max-w-md p-6 space-y-4">
      <h1 className="text-lg font-bold">公開を停止しています</h1>
      <p className="text-sm">
        現在このサイトは公開を停止しています。再開の予定は未定です。
      </p>
      <p className="text-sm">
        XINXIN の出演情報は公式 X をご確認ください。
      </p>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        お問い合わせ・削除要請の窓口は準備中です。
      </p>
    </main>
  );
}
