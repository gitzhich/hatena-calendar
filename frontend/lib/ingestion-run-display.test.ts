import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { unparsedCountLabel } from "./ingestion-run-display.ts";

describe("unparsedCountLabel", () => {
  it("0 件は分かっているゼロとして出す", () => {
    assert.equal(unparsedCountLabel(0), "0 件");
  });

  it("正の件数はそのまま出す", () => {
    assert.equal(unparsedCountLabel(2), "2 件");
  });

  it("null は分からないので 0 件にしない", () => {
    assert.equal(unparsedCountLabel(null), "—");
  });

  it("フィールド欠落（undefined）も null と同じく分からない", () => {
    assert.equal(unparsedCountLabel(undefined), "—");
  });
});
