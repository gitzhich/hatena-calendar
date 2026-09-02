import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { readFileSync } from "node:fs";
import {
  SESSION_COOKIE_PATH,
  SESSION_TTL_SECONDS,
  csrfMatches,
  newSession,
  openSession,
  sealSession,
} from "./session.ts";

/**
 * 管理者セッションの暗号処理（docs/architecture.md 第 3.2 節）。
 *
 * ここが破れると管理者になりすませる（docs/security.md の資産表で「高」）。
 * 改ざん・期限切れ・鍵違いのすべてで拒否されることを確かめる。
 */
describe("session", () => {
  const SECRET = "test-session-secret-at-least-32-characters";
  let original: string | undefined;

  before(() => {
    original = process.env.SESSION_SECRET;
    process.env.SESSION_SECRET = SECRET;
  });
  after(() => {
    process.env.SESSION_SECRET = original;
  });

  it("封じて開くと元に戻る", async () => {
    const session = newSession();
    const opened = await openSession(await sealSession(session));
    assert.equal(opened?.exp, session.exp);
    assert.equal(opened?.csrf, session.csrf);
  });

  it("有効期限は 8 時間", () => {
    const session = newSession();
    const ttl = session.exp - Math.floor(Date.now() / 1000);
    assert.equal(SESSION_TTL_SECONDS, 8 * 60 * 60);
    assert.ok(Math.abs(ttl - SESSION_TTL_SECONDS) <= 2);
  });

  it("Cookie が無ければ null", async () => {
    assert.equal(await openSession(undefined), null);
    assert.equal(await openSession(""), null);
  });

  it("形式が不正なら null", async () => {
    for (const value of ["fake", "aaa.bbb", ".", "a.b.c", "AAAA."]) {
      assert.equal(await openSession(value), null, `値: ${value}`);
    }
  });

  it("1 文字でも改ざんすると null。GCM の認証タグが弾く", async () => {
    const sealed = await sealSession(newSession());
    const [iv, data] = sealed.split(".");
    const flipped = data.slice(0, -1) + (data.at(-1) === "A" ? "B" : "A");
    assert.equal(await openSession(`${iv}.${flipped}`), null);
  });

  it("別の鍵で封じたものは開けない", async () => {
    const sealed = await sealSession(newSession());
    process.env.SESSION_SECRET = "another-secret-that-is-also-32-chars-long!";
    assert.equal(await openSession(sealed), null);
    process.env.SESSION_SECRET = SECRET;
  });

  it("期限切れは null。Cookie の Max-Age に任せず中身でも見る", async () => {
    const expired = { exp: Math.floor(Date.now() / 1000) - 1, csrf: "x" };
    assert.equal(await openSession(await sealSession(expired)), null);
  });

  it("中身の型が違えば null。封じられていても信用しない", async () => {
    // 鍵を持つ相手が壊れた payload を封じてきた場合。
    // 復号できても中身の形が違えば拒否する
    const bad = [
      { exp: Math.floor(Date.now() / 1000) + 100 }, // csrf がない
      { csrf: "x" }, // exp がない
      { exp: "9999999999", csrf: "x" }, // exp が文字列
      { exp: Math.floor(Date.now() / 1000) + 100, csrf: 123 }, // csrf が数値
      {},
    ];
    for (const payload of bad) {
      const sealed = await sealSession(payload as unknown as Parameters<typeof sealSession>[0]);
      assert.equal(await openSession(sealed), null, `payload: ${JSON.stringify(payload)}`);
    }
  });

  it("鍵が未設定・短すぎるときは動かさない", async () => {
    process.env.SESSION_SECRET = "";
    await assert.rejects(() => sealSession(newSession()));
    process.env.SESSION_SECRET = "short";
    await assert.rejects(() => sealSession(newSession()));
    process.env.SESSION_SECRET = SECRET;
  });

  it("CSRF トークンは毎回変わる", () => {
    assert.notEqual(newSession().csrf, newSession().csrf);
  });
});

describe("csrfMatches", () => {
  it("一致すれば true", () => {
    assert.equal(csrfMatches("abc123", "abc123"), true);
  });

  it("不一致・長さ違い・文字列でない値は false", () => {
    assert.equal(csrfMatches("abc123", "abc124"), false);
    assert.equal(csrfMatches("abc123", "abc12"), false);
    assert.equal(csrfMatches("abc123", ""), false);
    assert.equal(csrfMatches("abc123", undefined), false);
    assert.equal(csrfMatches("abc123", null), false);
    assert.equal(csrfMatches("abc123", 123), false);
  });
});

/**
 * セッション Cookie を送る範囲（docs/security.md T-02 / ADR-0016）。
 *
 * **ソースを読んで確かめる。** Cookie を実際に発行するには Next.js の
 * リクエストコンテキストが要り、単体では動かせない。ここで守りたいのは
 * 「公開ページに送らない」という規約そのものなので、その規約を検査する。
 */
describe("セッション Cookie の適用範囲", () => {
  const actions = readFileSync(new URL("../app/admin/actions.ts", import.meta.url), "utf8");

  it("公開ページには送らない。path はルートではない", () => {
    assert.notEqual(SESSION_COOKIE_PATH, "/");
    assert.ok(SESSION_COOKIE_PATH.startsWith("/admin"));
  });

  it("set がリテラルの path を持たず、定数を使う", () => {
    // path: "/" と書き戻されると、公開ページの XSS から管理操作へ繋がる
    // 経路が復活する（ADR-0016）
    assert.doesNotMatch(actions, /path:\s*"\/"/);
    assert.match(actions, /path:\s*SESSION_COOKIE_PATH/);
  });

  it("delete も同じ path を渡す。省くとログアウトで消えない", () => {
    assert.doesNotMatch(actions, /delete\(SESSION_COOKIE\)/);
    assert.match(actions, /delete\(\{\s*name:\s*SESSION_COOKIE,\s*path:\s*SESSION_COOKIE_PATH\s*\}\)/);
  });
});
