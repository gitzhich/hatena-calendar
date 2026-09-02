import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MAX_LOGIN_ATTEMPTS,
  allowLogin,
  recordLoginFailure,
  recordLoginSuccess,
} from "./login-rate-limit.ts";

/**
 * ログイン試行のレート制限（FR-20 / T-02）。
 *
 * モジュール内のカウンタを共有するため、テストごとに違うキーを使う。
 * 実時間には依存しない（ウィンドウを跨ぐ検証は rate-limit.test.ts が持つ）。
 */

test("上限までは試行できる", () => {
  const ip = "203.0.113.1";
  for (let i = 0; i < MAX_LOGIN_ATTEMPTS; i += 1) {
    assert.equal(allowLogin(ip), true, `${i + 1} 回目は許可される`);
    recordLoginFailure(ip);
  }
  assert.equal(allowLogin(ip), false, "上限に達したら拒否する");
});

test("判定だけでは消費しない。正しいパスワードの管理者を締め出さない", () => {
  const ip = "203.0.113.2";
  for (let i = 0; i < 100; i += 1) {
    assert.equal(allowLogin(ip), true);
  }
});

test("成功したらカウンタを捨てる", () => {
  const ip = "203.0.113.3";
  for (let i = 0; i < MAX_LOGIN_ATTEMPTS; i += 1) recordLoginFailure(ip);
  assert.equal(allowLogin(ip), false);

  recordLoginSuccess(ip);
  assert.equal(allowLogin(ip), true, "成功後は再び試行できる");
});

test("IP ごとに独立している。他人の失敗で締め出されない", () => {
  const victim = "203.0.113.4";
  const attacker = "203.0.113.5";
  for (let i = 0; i < MAX_LOGIN_ATTEMPTS; i += 1) recordLoginFailure(attacker);

  assert.equal(allowLogin(attacker), false);
  assert.equal(allowLogin(victim), true);
});

test("上限は 5 回。緩めるとパスワード総当たりに近づく", () => {
  assert.equal(MAX_LOGIN_ATTEMPTS, 5);
});
