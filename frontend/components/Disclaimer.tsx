import { CONTACT_EMAIL } from "@/lib/contact";

/**
 * 非公式である旨の明示（FR-07 / LR-01）と、削除要請の窓口（LR-05 / ADR-0017）。
 *
 * **ルートレイアウトが出す。ページごとに置かない。** 以前は CalendarPage の中にあり、
 * そこを通らないページ（/unavailable など）には出ていなかった。
 * ページごとに置くと、新しいページを足したときに忘れる。
 *
 * アドレスは lib/contact.ts の定数を使う。ここに直接書かない。
 *
 * 免責の文面は docs/requirements.md 第 12 章の未決定事項 1。ここは仮置き。
 * 連絡先の書き方は ADR-0017 で決着している。
 */
export function Disclaimer() {
  return (
    <footer
      id="disclaimer"
      className="mx-auto max-w-2xl px-4 pb-8 mt-10 text-xs text-neutral-600 dark:text-neutral-400"
    >
      <div className="border-t border-neutral-300 dark:border-neutral-700 pt-4 space-y-2">
        <p>
          本サイトは<strong>ファンが個人で制作した非公式のツール</strong>です。
          XINXIN および運営とは関係がなく、公認も受けていません。
        </p>
        <p>
          掲載内容に誤りが含まれることがあります。
          <strong>最新・正確な情報は公式 X をご確認ください。</strong>
        </p>
        <p>
          掲載内容の削除要請・お問い合わせ:{" "}
          <a href={`mailto:${CONTACT_EMAIL}`} className="underline break-all">
            {CONTACT_EMAIL}
          </a>
        </p>
        <p>
          ご連絡の際は、<strong>対象の出演情報と該当ページの URL</strong>
          を添えていただけると、対応が早くなります。
        </p>
      </div>
    </footer>
  );
}
