import "server-only";

/**
 * ログイン試行のレート制限（FR-20 / T-02）。
 *
 * **Spring Boot 側にも同じ制限がある**（docs/api.md 第 6 章）。
 * こちらは実クライアントの IP で絞れるのが利点。Spring Boot 側から見た
 * 送信元は Vercel の egress IP なので、そこだけでは全利用者が
 * まとめて絞られてしまう（docs/architecture.md 第 5.5 節）。
 *
 * プロセス内のメモリに持つ。サーバレスでは実行環境をまたぐと共有されないが、
 * **これは 2 枚あるうちの 1 枚**であり、Spring Boot 側が最後の砦になる。
 */
const MAX_ATTEMPTS = 5;
const WINDOW_MS = 15 * 60 * 1000;

const attempts = new Map<string, { count: number; firstAt: number }>();

export function allowLogin(key: string): boolean {
  const record = attempts.get(key);
  if (!record) return true;
  if (Date.now() - record.firstAt > WINDOW_MS) {
    attempts.delete(key);
    return true;
  }
  return record.count < MAX_ATTEMPTS;
}

export function recordLoginFailure(key: string): void {
  const record = attempts.get(key);
  if (!record || Date.now() - record.firstAt > WINDOW_MS) {
    attempts.set(key, { count: 1, firstAt: Date.now() });
    return;
  }
  record.count += 1;
}

export function recordLoginSuccess(key: string): void {
  attempts.delete(key);
}
