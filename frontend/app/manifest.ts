import type { MetadataRoute } from "next";

/**
 * ウェブアプリマニフェスト。ホーム画面に追加したときの見え方を決める。
 *
 * **192 / 512 / maskable のアイコンはここからしか参照されない。**
 * `app/icon.svg` と `app/apple-icon.png` は Next.js のファイル規約で
 * 自動的に <link> が張られるが、この 3 つは manifest が要る。
 *
 * `theme_color` は置かない。ライトとダークで配色が変わる作りなので
 * （`app/globals.css` の `prefers-color-scheme`）、単色を指定すると
 * どちらかでブラウザの色と噛み合わなくなる。
 *
 * CSP は `default-src 'self'` があり `manifest-src` を持たないため、
 * 同一オリジンのこれは既定で許可される（`lib/security-headers.ts`）。
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "XINXIN 出演カレンダー（非公式）",
    // ホーム画面のラベルは 12 文字程度で切られる。非公式であることは
    // ページ本文で必ず示している（LR-01）ので、ここは短さを優先する
    short_name: "XINXIN カレンダー",
    description:
      "XINXIN の出演情報をカレンダーで見られる、ファン制作の非公式ツールです。",
    start_url: "/",
    display: "standalone",
    background_color: "#1F2430",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      {
        // 角を丸く切られても図柄が欠けない版。any と兼用にしない
        // （兼用にすると、切られない環境で余白が大きく見える）
        src: "/icons/icon-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
