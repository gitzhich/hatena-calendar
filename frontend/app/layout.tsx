import type { Metadata } from "next";
import { M_PLUS_Rounded_1c } from "next/font/google";
import "./globals.css";
import { Disclaimer } from "@/components/Disclaimer";

const rounded = M_PLUS_Rounded_1c({
  subsets: ["latin"],
  weight: ["400", "700"],
  display: "swap",
  variable: "--font-rounded",
});

export const metadata: Metadata = {
  title: "XINXIN 出演カレンダー（非公式）",
  description:
    "XINXIN の出演情報をカレンダーで見られる、ファン制作の非公式ツールです。",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ja" className={rounded.variable} suppressHydrationWarning>
      <body
        className={`${rounded.className} antialiased text-neutral-900 dark:text-neutral-100 bg-white dark:bg-neutral-950`}
      >
        {children}
        {/* 全ページに出す（LR-01 / LR-05）。ページごとに置くと新しいページで忘れる */}
        <Disclaimer />
      </body>
    </html>
  );
}
