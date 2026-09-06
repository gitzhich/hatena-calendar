import type { Metadata } from "next";
import { CONTACT_EMAIL, CONTACT_REQUEST_ITEMS } from "@/lib/contact";

export const metadata: Metadata = { title: "公開を停止しています" };

/**
 * 停止中の案内（ADR-0013）。proxy.ts が SITE_DISABLED のとき ここへ rewrite する。
 *
 * **削除要請の窓口をここに出す。** 停止中こそ要請の最中であり、
 * 要請者が状況を確認できないままサイトが消える状態にしない。
 *
 * `proxy.ts` は停止中、/admin と このページ以外の全パスを書き換える。
 * **窓口をサイト内のフォームにすると、ここで到達できなくなる**
 * （ADR-0017 の却下理由）。外部で完結するメールにしてある。
 */
export default function Unavailable() {
  return (
    <main className="public-theme min-h-screen bg-canvas text-ink">
      <div className="mx-auto max-w-md px-6 py-8 space-y-4">
        <h1 className="text-lg font-bold">公開を停止しています</h1>
        <p className="text-sm">
          現在このサイトは公開を停止しています。再開の予定は未定です。
        </p>
        <p className="text-sm">XINXIN の出演情報は公式 X をご確認ください。</p>

        <section className="rounded-card bg-surface shadow-card p-4 space-y-2">
          <h2 className="text-sm font-bold">お問い合わせ・削除要請</h2>
          <p className="text-sm">
            <a href={`mailto:${CONTACT_EMAIL}`} className="underline break-all text-accent">
              {CONTACT_EMAIL}
            </a>
          </p>
          <p className="text-xs text-muted">
            次の内容を添えていただけると、対応が早くなります。
          </p>
          <ul className="list-disc pl-5 text-xs text-muted">
            {CONTACT_REQUEST_ITEMS.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>
      </div>
    </main>
  );
}
