/**
 * 管理者セッション（docs/architecture.md「管理者の認証フロー」）。
 *
 * **"server-only" を付けていない。** proxy.ts（Edge ランタイム）と
 * Server Component の両方から使うのと、暗号処理を素の Node で
 * 単体テストするため。秘密の読み出しは呼び出し時の環境変数からで、
 * このモジュール自体は秘密を抱えない。
 * 管理キーを読む lib/admin-api.ts のほうには "server-only" を付けてある。
 *
 * **セッションストアを持たない。** 署名・暗号化した Cookie に
 * 「管理者としてログイン済み」と有効期限だけを入れる。単一管理者であり、
 * Redis などを増やす必要がない。
 *
 * AES-256-GCM を使う。GCM は暗号化と改ざん検知を兼ねるので、
 * 署名を別に持つ必要がない。Web Crypto で書いているのは、
 * Node ランタイムと middleware（Edge）の両方から同じ検証を行うため。
 *
 * **制約: 発行済みセッションを即時失効できない。** ログアウトは Cookie の削除で
 * 行うため、事前に複製されていれば有効期限まで使える。単一管理者で被害範囲が
 * 限定されること、有効期限を 8 時間に絞ることで許容する。
 * 緊急時は SESSION_SECRET の再生成で全セッションを無効化する。
 */

export const SESSION_COOKIE = "admin_session";

/**
 * セッション Cookie を送る範囲（NFR-03 / docs/security.md T-02）。
 *
 * **公開ページに送らない。** ルート全体に配ると、公開ページで XSS が成立した場合に
 * 同一オリジンで `/admin` を読み、CSRF トークンを取り出して管理操作を実行できる
 * 経路が残る。HttpOnly は「JS から値を読めない」だけで、
 * **ブラウザが自動で付けて送ることは止められない**。
 *
 * 公開ページはセッションを読まないため、絞っても失うものがない。
 *
 * **set と delete で同じ値を使うこと。** path が食い違うと削除できず、
 * ログアウトしたつもりで Cookie が残る。
 */
export const SESSION_COOKIE_PATH = "/admin";
export const SESSION_TTL_SECONDS = 8 * 60 * 60;

export type Session = {
  /** 失効時刻（epoch 秒）。 */
  exp: number;
  /** CSRF トークン。管理操作のフォームに埋め、送信時に突き合わせる。 */
  csrf: string;
};

function secretMaterial(): ArrayBuffer {
  const secret = process.env.SESSION_SECRET;
  if (!secret || secret.length < 32) {
    // 未設定や短すぎる鍵で動かさない。設定漏れが「誰でもセッションを
    // 偽造できる」状態になるより、起動しないほうがよい
    throw new Error("SESSION_SECRET が未設定か短すぎます（32 文字以上）");
  }
  const bytes = new TextEncoder().encode(secret);
  return bytes.buffer.slice(0, bytes.byteLength) as ArrayBuffer;
}

async function key(): Promise<CryptoKey> {
  const digest = await crypto.subtle.digest("SHA-256", secretMaterial());
  return crypto.subtle.importKey("raw", digest, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}

const b64 = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");

/**
 * base64url を ArrayBuffer に戻す。
 *
 * Uint8Array をそのまま crypto.subtle に渡すと、TypeScript が
 * ArrayBufferLike（SharedArrayBuffer を含む）と見て BufferSource に
 * 代入できないと判断する。ArrayBuffer を切り出して渡す。
 */
function unb64(text: string): ArrayBuffer {
  const bytes = Buffer.from(text, "base64url");
  return bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
}

export async function sealSession(session: Session): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(JSON.stringify(session));
  const plaintext = encoded.buffer.slice(0, encoded.byteLength) as ArrayBuffer;
  const sealed = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv.buffer.slice(0, iv.byteLength) as ArrayBuffer },
    await key(),
    plaintext,
  );
  return `${b64(iv)}.${b64(new Uint8Array(sealed))}`;
}

/** 復号・検証する。改ざん、期限切れ、形式不正はすべて null を返す。 */
export async function openSession(value: string | undefined): Promise<Session | null> {
  if (!value) return null;
  const [ivPart, dataPart] = value.split(".");
  if (!ivPart || !dataPart) return null;

  try {
    const opened = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: unb64(ivPart) },
      await key(),
      unb64(dataPart),
    );
    const session = JSON.parse(new TextDecoder().decode(opened)) as Session;
    if (typeof session.exp !== "number" || typeof session.csrf !== "string") {
      return null;
    }
    // 期限切れは無効。Cookie の Max-Age に任せず中身でも見る
    if (session.exp * 1000 < Date.now()) return null;
    return session;
  } catch {
    // 改ざんされていれば GCM の認証タグ検証で例外になる
    return null;
  }
}

export function newSession(): Session {
  return {
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS,
    csrf: b64(crypto.getRandomValues(new Uint8Array(32))),
  };
}

/**
 * CSRF トークンの照合。**固定時間で比べる。**
 *
 * 早期 return すると、応答時間の差からトークンを 1 文字ずつ推測されうる。
 */
export function csrfMatches(expected: string, provided: unknown): boolean {
  if (typeof provided !== "string" || provided.length !== expected.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < expected.length; i++) {
    diff |= expected.charCodeAt(i) ^ provided.charCodeAt(i);
  }
  return diff === 0;
}
