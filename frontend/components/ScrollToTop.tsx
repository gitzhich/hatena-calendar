"use client";

import { useEffect, useState } from "react";

const SHOW_AFTER_PX = 320;

/** スクロール後に右下へ出す。ページ全体は Server Component のまま。 */
export function ScrollToTop() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => {
      setVisible(window.scrollY > SHOW_AFTER_PX);
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  if (!visible) return null;

  return (
    <button
      type="button"
      className="scroll-to-top fixed z-40 inline-flex min-h-11 min-w-11 items-center justify-center gap-1 rounded-full border border-line bg-surface px-3.5 text-sm font-medium text-ink shadow-card hover:bg-canvas focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent bottom-[max(1rem,env(safe-area-inset-bottom))] right-[max(1rem,env(safe-area-inset-right))]"
      onClick={() => {
        const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        window.scrollTo({ top: 0, behavior: reduce ? "auto" : "smooth" });
      }}
    >
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" className="shrink-0">
        <path
          fill="currentColor"
          d="M8 3.2 2.6 8.6l1.1 1.1L7.2 6.2V13h1.6V6.2l3.5 3.5 1.1-1.1z"
        />
      </svg>
      上へ
    </button>
  );
}
