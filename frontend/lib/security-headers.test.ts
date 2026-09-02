import { test } from "node:test";
import assert from "node:assert/strict";
import { buildCsp, createNonce, HSTS, REFERRER_POLICY } from "./security-headers.ts";

const directive = (csp: string, name: string): string => {
  const found = csp.split("; ").find((d) => d.startsWith(`${name} `) || d === name);
  assert.ok(found, `${name} が CSP に無い`);
  return found;
};

test("公開ページ（nonce なし）でも script-src 以外は締まっている", () => {
  const csp = buildCsp();
  assert.equal(directive(csp, "frame-ancestors"), "frame-ancestors 'none'");
  assert.equal(directive(csp, "object-src"), "object-src 'none'");
  assert.equal(directive(csp, "base-uri"), "base-uri 'self'");
  assert.equal(directive(csp, "form-action"), "form-action 'self'");
  assert.equal(directive(csp, "connect-src"), "connect-src 'self'");
  assert.equal(directive(csp, "default-src"), "default-src 'self'");
});

test("nonce なしの script-src は unsafe-inline を許す（ISR を保つため）", () => {
  assert.match(directive(buildCsp(), "script-src"), /'unsafe-inline'/);
});

test("nonce ありなら unsafe-inline を許さず strict-dynamic になる", () => {
  const csp = buildCsp({ nonce: "abc123" });
  const script = directive(csp, "script-src");
  assert.match(script, /'nonce-abc123'/);
  assert.match(script, /'strict-dynamic'/);
  assert.doesNotMatch(script, /'unsafe-inline'/);
});

test("本番では unsafe-eval を許さない", () => {
  assert.doesNotMatch(buildCsp(), /'unsafe-eval'/);
  assert.doesNotMatch(buildCsp({ nonce: "abc123" }), /'unsafe-eval'/);
});

test("開発時だけ unsafe-eval を許す（React がスタック復元に eval を使う）", () => {
  assert.match(buildCsp({ isDev: true }), /'unsafe-eval'/);
  assert.match(buildCsp({ nonce: "abc123", isDev: true }), /'unsafe-eval'/);
});

test("upgrade-insecure-requests は本番だけ。開発はローカルの HTTP を壊さない", () => {
  assert.match(buildCsp(), /upgrade-insecure-requests/);
  assert.doesNotMatch(buildCsp({ isDev: true }), /upgrade-insecure-requests/);
});

test("外部ホストをどこにも許可していない", () => {
  for (const csp of [buildCsp(), buildCsp({ nonce: "abc123" })]) {
    assert.doesNotMatch(csp, /https?:\/\//);
    assert.doesNotMatch(csp, /\*/);
  }
});

test("nonce は毎回変わり、CSP のトークンとして安全な文字だけを含む", () => {
  const a = createNonce();
  const b = createNonce();
  assert.notEqual(a, b);
  assert.match(a, /^[0-9a-f]{32}$/);
});

test("HSTS と Referrer-Policy の値", () => {
  assert.match(HSTS, /^max-age=\d+/);
  assert.match(HSTS, /includeSubDomains/);
  assert.equal(REFERRER_POLICY, "strict-origin-when-cross-origin");
});
