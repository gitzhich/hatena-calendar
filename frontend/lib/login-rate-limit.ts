/*
 * server-only を付けていない。カウンタは秘密を持たず、
 * 付けると node --test から読めなくなる（rate-limit.ts と同じ判断）。
 * サーバ境界は呼び出し側（app/admin/actions.ts）が守る。
 */
import { createRateLimiter } from "./rate-limit.ts";

/**
 * ログイン試行のレート制限（FR-20 / T-02）。
 *
 * **Spring Boot 側にも同じ制限がある**（docs/api.md「内部 API」）。
 * こちらは実クライアントの IP で絞れるのが利点。Spring Boot 側から見た
 * 送信元は Vercel の egress IP なので、そこだけでは全利用者が
 * まとめて絞られてしまう（docs/architecture.md「公開ページのレート制限」）。
 *
 * **数えるのは失敗だけ。** 成功したらカウンタを捨てる。
 * 正しいパスワードを入れている管理者を締め出さないため。
 * 公開ページの制限（proxy.ts）が全リクエストを数えるのとはここが違う。
 *
 * プロセス内のメモリに持つ。サーバレスでは実行環境をまたぐと共有されないが、
 * **これは 2 枚あるうちの 1 枚**であり、Spring Boot 側が最後の砦になる。
 */
export const MAX_LOGIN_ATTEMPTS = 5;
export const LOGIN_WINDOW_MS = 15 * 60 * 1000;

const limiter = createRateLimiter({
  limit: MAX_LOGIN_ATTEMPTS,
  windowMs: LOGIN_WINDOW_MS,
});

/** 試行してよいか。**ここでは消費しない。** */
export function allowLogin(key: string): boolean {
  return limiter.allow(key);
}

export function recordLoginFailure(key: string): void {
  limiter.hit(key);
}

export function recordLoginSuccess(key: string): void {
  limiter.reset(key);
}
