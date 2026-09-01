/**
 * 非公式である旨の明示（FR-07 / LR-01）。**全ページのフッタに出す。**
 *
 * 文面は docs/requirements.md 第 12 章の未決定事項。ここは仮置き。
 */
export function Disclaimer() {
  return (
    <footer
      id="disclaimer"
      className="mt-10 border-t border-neutral-300 dark:border-neutral-700 pt-4 text-xs text-neutral-600 dark:text-neutral-400 space-y-2"
    >
      <p>
        本サイトは<strong>ファンが個人で制作した非公式のツール</strong>です。
        XINXIN および運営とは関係がなく、公認も受けていません。
      </p>
      <p>
        掲載内容に誤りが含まれることがあります。
        <strong>最新・正確な情報は公式 X をご確認ください。</strong>
      </p>
    </footer>
  );
}
