import { CONTACT_EMAIL } from "@/lib/contact";

/**
 * 情報源アカウント（FR-07 / LR-01）。
 *
 * **ハンドルの正本はバックエンドの `source_account.username`**（出典 URL の生成に使う）。
 * ここはフッタの固定リンク 1 か所だけなので、API を 1 本増やすより定数で持つ
 * （docs/coding-guidelines.md「定数の置き場所」「定数はそれを解釈する側に置く」）。
 * ハンドルが変わったら両方を直す。
 */
const OFFICIAL_X_HANDLE = "xinxin_official";
const OFFICIAL_X_URL = `https://x.com/${OFFICIAL_X_HANDLE}`;

/**
 * 非公式である旨の明示（FR-07 / LR-01）と、削除要請の窓口（LR-05 / ADR-0017）。
 *
 * **ルートレイアウトが出す。ページごとに置かない。** 以前は CalendarPage の中にあり、
 * そこを通らないページ（/unavailable など）には出ていなかった。
 * ページごとに置くと、新しいページを足したときに忘れる。
 *
 * アドレスは lib/contact.ts の定数を使う。ここに直接書かない。
 *
 * **この文面が正本。** 2026-09-03 から本番で稼働しているものをそのまま正式版とした
 * （docs/requirements.md FR-07）。変えるときは受入基準の 3 点
 * ——非公式である旨・公式 X への導線・削除要請の窓口——を落とさないこと。
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
          <strong>
            最新・正確な情報は{" "}
            <a
              href={OFFICIAL_X_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="underline"
            >
              公式 X（@{OFFICIAL_X_HANDLE}）
            </a>{" "}
            をご確認ください。
          </strong>
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
