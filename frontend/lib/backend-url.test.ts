import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";

import { backendBaseUrl } from "./backend-url.ts";

const original = process.env.BACKEND_BASE_URL;

afterEach(() => {
  if (original === undefined) delete process.env.BACKEND_BASE_URL;
  else process.env.BACKEND_BASE_URL = original;
});

describe("backendBaseUrl", () => {
  it("設定されていればその値を使う", () => {
    process.env.BACKEND_BASE_URL = "https://example.fly.dev";
    assert.equal(backendBaseUrl(), "https://example.fly.dev");
  });

  it("空文字は未設定として扱う", () => {
    // 雛形から作った .env.local には BACKEND_BASE_URL= の空行が残る。
    // ?? だと空文字が採用され、リクエスト先が壊れる
    process.env.BACKEND_BASE_URL = "";
    assert.equal(backendBaseUrl(), "http://localhost:8080");
  });

  it("空白だけの値も未設定として扱う", () => {
    process.env.BACKEND_BASE_URL = "   ";
    assert.equal(backendBaseUrl(), "http://localhost:8080");
  });

  it("未設定ならローカルの既定値", () => {
    delete process.env.BACKEND_BASE_URL;
    assert.equal(backendBaseUrl(), "http://localhost:8080");
  });

  it("末尾のスラッシュを落とす", () => {
    // パスを連結するので、残っていると //api/... になる
    process.env.BACKEND_BASE_URL = "https://example.fly.dev/";
    assert.equal(backendBaseUrl(), "https://example.fly.dev");
  });
});
