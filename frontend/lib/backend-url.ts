/*
 * server-only を付けていない。この値自体は秘密ではなく、
 * 付けると node --test から読めなくなる。サーバ境界は呼び出し側
 * （api.ts / admin-api.ts）が守る。
 */

/** ローカル開発の既定値。compose と bootRun の組み合わせ。 */
const LOCAL_DEFAULT = "http://localhost:8080";

/**
 * Spring Boot のベース URL。
 *
 * <p><b>空文字を「未設定」として扱う。</b>`?? 既定値` は null と undefined
 * にしか反応せず、空文字には反応しない。雛形から作った `.env.local` には
 * `BACKEND_BASE_URL=` の空行が残るため、素直に書くと既定値が効かず
 * リクエスト先が壊れる。
 *
 * <p>同じ罠を backend の `${VAR:default}` でも踏んでいる
 * （docs/architecture.md「設定と環境変数」）。空文字は「設定済み」と見なされる。
 */
export function backendBaseUrl(): string {
  const configured = process.env.BACKEND_BASE_URL?.trim();
  return configured ? configured.replace(/\/+$/, "") : LOCAL_DEFAULT;
}
