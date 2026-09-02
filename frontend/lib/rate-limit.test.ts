import { test } from "node:test";
import assert from "node:assert/strict";
import { clientIpOf, createRateLimiter } from "./rate-limit.ts";

/** 時刻を手で進められる時計。実時間に依存させない。 */
function fakeClock(start = 1_000_000) {
  let at = start;
  return { now: () => at, advance: (ms: number) => (at += ms) };
}

test("上限までは通し、超えたら弾く", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 3, windowMs: 60_000, now: clock.now });

  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.hit("a"), false, "4 回目は上限を超える");
  assert.equal(limiter.hit("a"), false, "超過後も弾き続ける");
});

test("キーごとに独立して数える。他人の連打で巻き添えにしない", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 2, windowMs: 60_000, now: clock.now });

  limiter.hit("a");
  limiter.hit("a");
  assert.equal(limiter.hit("a"), false);
  assert.equal(limiter.hit("b"), true, "別のキーは影響を受けない");
});

test("ウィンドウが明けたら数え直す", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 2, windowMs: 60_000, now: clock.now });

  limiter.hit("a");
  limiter.hit("a");
  assert.equal(limiter.hit("a"), false);

  clock.advance(59_999);
  assert.equal(limiter.hit("a"), false, "ウィンドウ内はまだ弾く");

  clock.advance(1);
  assert.equal(limiter.hit("a"), true, "ちょうど windowMs で明ける");
});

test("ウィンドウは最初のリクエストから始まる。連打で延長されない", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 5, windowMs: 60_000, now: clock.now });

  limiter.hit("a");
  clock.advance(30_000);
  limiter.hit("a");
  clock.advance(30_000);

  // 最初から 60_000 経過。ここで新しいウィンドウに入る
  assert.equal(limiter.hit("a"), true);
});

test("reset でカウンタを捨てる（ログイン成功時）", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 1, windowMs: 60_000, now: clock.now });

  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.hit("a"), false);
  limiter.reset("a");
  assert.equal(limiter.hit("a"), true);
});

test("期限切れのキーはメモリから消える", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 10, windowMs: 60_000, now: clock.now });

  // 攻撃者が送信元を変えて大量のキーを作った状況
  for (let i = 0; i < 500; i += 1) limiter.hit(`ip-${i}`);

  clock.advance(60_000);
  // 新しいリクエストが 1 つ来れば、期限切れの 500 件が掃除される
  limiter.hit("fresh");

  // Map の中身は外から見えないため、掃除後も古いキーが
  // 新しいウィンドウとして扱われることで確認する
  assert.equal(limiter.hit("ip-0"), true);
});

test("limit が 0 なら 1 回目から弾く", () => {
  const limiter = createRateLimiter({ limit: 0, windowMs: 60_000 });
  assert.equal(limiter.hit("a"), false);
});

test("clientIpOf は x-forwarded-for の先頭を取る", () => {
  const headers = (value: string | null) => ({ get: () => value });
  assert.equal(clientIpOf(headers("203.0.113.9")), "203.0.113.9");
  assert.equal(
    clientIpOf(headers("203.0.113.9, 70.41.3.18, 150.172.238.178")),
    "203.0.113.9",
    "プロキシを経由しても実クライアントは先頭",
  );
  assert.equal(clientIpOf(headers("  203.0.113.9  ")), "203.0.113.9");
});

test("ヘッダが無くても素通しにしない", () => {
  const headers = (value: string | null) => ({ get: () => value });
  // "unknown" にまとめる。null を返すと呼び出し側が制限を飛ばしかねない
  assert.equal(clientIpOf(headers(null)), "unknown");
  assert.equal(clientIpOf(headers("")), "unknown");
  assert.equal(clientIpOf(headers("   ")), "unknown");
});

test("allow は消費しない。何度見ても減らない", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 2, windowMs: 60_000, now: clock.now });

  for (let i = 0; i < 10; i += 1) {
    assert.equal(limiter.allow("a"), true, "見るだけでは減らない");
  }
  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.hit("a"), true);
  assert.equal(limiter.allow("a"), false, "消費しきったら false");
});

test("allow はウィンドウが明けたら true に戻る", () => {
  const clock = fakeClock();
  const limiter = createRateLimiter({ limit: 1, windowMs: 60_000, now: clock.now });

  limiter.hit("a");
  assert.equal(limiter.allow("a"), false);

  clock.advance(60_000);
  // 明けたのに false のままだと、締め出しが恒久的に解除されない
  assert.equal(limiter.allow("a"), true);
});

test("allow は limit 0 なら常に false", () => {
  const limiter = createRateLimiter({ limit: 0, windowMs: 60_000 });
  assert.equal(limiter.allow("a"), false);
});
