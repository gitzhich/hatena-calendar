/*
 * server-only を付けていない。カウンタは秘密を持たず、
 * 付けると node --test から読めなくなる（backend-url.ts と同じ判断）。
 * サーバ境界は呼び出し側（proxy.ts / app/admin/actions.ts）が守る。
 */

/**
 * 固定ウィンドウのレート制限カウンタ（NFR-03 / docs/security.md T-04）。
 *
 * **プロセス内のメモリに持つ。** Vercel ではインスタンスをまたぐと共有されず、
 * 実行環境が変われば数え直しになる。それでも置くのは、これが
 * **多層防御の 1 枚**だからで、単独で完全を目指していない
 * （docs/architecture.md「公開ページのレート制限」）。
 *
 * **限界をもう 1 つ。** 弾いたリクエストも Vercel の関数呼び出しを 1 回消費する。
 * レート制限は Vercel の枠を守り切るものではなく、
 * **ページレンダリング・バックエンド往復・ISR 再生成を避けてリクエスト単価を下げる**もの。
 *
 * 固定ウィンドウを選んだ理由: 実装が数行で、状態が 1 キーあたり 2 つの数値で済む。
 * 境目で瞬間的に上限の 2 倍を通す弱点があるが、
 * ここでの目的は連打の抑止であり、正確な流量制御ではない。
 */
export type RateLimiter = {
  /** 1 回消費して、まだ枠内かを返す。false なら超過。 */
  hit(key: string): boolean;
  /**
   * <b>消費せずに</b>枠内かを見る。
   *
   * <p>ログインのように「判定してから、失敗したときだけ数える」用途で使う。
   * 判定のたびに消費すると、正しいパスワードを入れた管理者まで枠を食う。
   */
  allow(key: string): boolean;
  /** そのキーのカウンタを捨てる。ログイン成功時など。 */
  reset(key: string): void;
};

export function createRateLimiter(options: {
  limit: number;
  windowMs: number;
  /** テストから時刻を固定するため。既定は Date.now */
  now?: () => number;
}): RateLimiter {
  const { limit, windowMs, now = Date.now } = options;
  const counters = new Map<string, { count: number; startedAt: number }>();

  return {
    hit(key) {
      const at = now();
      const current = counters.get(key);

      if (current === undefined || at - current.startedAt >= windowMs) {
        counters.set(key, { count: 1, startedAt: at });
        // メモリを無限に増やさない。期限切れの入れ物をここで掃除する
        prune(counters, at, windowMs);
        return limit >= 1;
      }

      current.count += 1;
      return current.count <= limit;
    },

    allow(key) {
      const current = counters.get(key);
      if (current === undefined) {
        return limit >= 1;
      }
      if (now() - current.startedAt >= windowMs) {
        // 明けたウィンドウを残さない。残すと解除されないまま見え続ける
        counters.delete(key);
        return limit >= 1;
      }
      return current.count < limit;
    },

    reset(key) {
      counters.delete(key);
    },
  };
}

/**
 * 期限切れのキーを捨てる。
 *
 * <b>放置するとメモリが増え続ける。</b> キーはクライアント IP であり、
 * 攻撃者は送信元を変えて無数のキーを作れる。
 */
function prune(
  counters: Map<string, { count: number; startedAt: number }>,
  at: number,
  windowMs: number,
): void {
  for (const [key, value] of counters) {
    if (at - value.startedAt >= windowMs) {
      counters.delete(key);
    }
  }
}

/**
 * リクエストからクライアントの IP を取り出す。
 *
 * <b>Vercel が付ける x-forwarded-for の先頭を見る。</b> Spring Boot 側から見た
 * 送信元は Vercel の egress IP なので、実クライアントを見られるのはここだけ
 * （docs/architecture.md「公開ページのレート制限」）。
 *
 * 取れない場合は "unknown" にまとめる。**素通しにしない。**
 * ヘッダを落とせば制限を外せる、という抜け道を作らないため。
 */
export function clientIpOf(headers: { get(name: string): string | null }): string {
  return headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}
