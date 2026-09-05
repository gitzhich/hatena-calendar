import type { NextConfig } from "next";
import { HSTS, NOSNIFF, REFERRER_POLICY } from "./lib/security-headers.ts";

/**
 * 常時付けるセキュリティヘッダ（NFR-03 / docs/security.md「対策が効く場所の一覧」）。
 *
 * **CSP はここに置かない。** proxy.ts が付ける。両方から出すと
 * `Content-Security-Policy` が 2 本になり、ブラウザは両方を同時に適用する
 * （lib/security-headers.ts の説明を参照）。
 *
 * こちらに置くのは、**静的アセットにも付けたいヘッダ**だけ。
 * proxy.ts の matcher は `_next/static` を除外しているため、
 * JS / CSS に `nosniff` を効かせられるのはこの経路しかない。
 */
const nextConfig: NextConfig = {
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: NOSNIFF },
          { key: "Referrer-Policy", value: REFERRER_POLICY },
          // HTTP で配信されるローカルでは無視されるが、
          // 本番と同じ設定を通しておく
          { key: "Strict-Transport-Security", value: HSTS },
        ],
      },
    ];
  },
};

export default nextConfig;
