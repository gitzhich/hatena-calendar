import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MONTHLY_BUDGET_USD,
  USD_PER_RESOURCE,
  estimateUsd,
  overBudget,
} from "./ingestion-cost.ts";

test("単価は 1 リソース $0.005（CLAUDE.md の料金体系）", () => {
  assert.equal(USD_PER_RESOURCE, 0.005);
});

test("概算コストは 2 桁で丸める", () => {
  assert.equal(estimateUsd(4), "$0.02");
  assert.equal(estimateUsd(300), "$1.50");
  assert.equal(estimateUsd(1000), "$5.00");
});

test("ちょうど半セントは切り上げる。浮動小数のまま丸めると切り下がる", () => {
  // 3 * 0.005 = $0.015 ちょうど。2 進数では 0.015 をわずかに下回る値になるため、
  // (3 * 0.005).toFixed(2) は "0.01" になる。セントの整数に丸めてから割ればずれない。
  // 0〜5000 リソースのうち 822 件でこの差が出る
  assert.equal(estimateUsd(3), "$0.02");
  assert.equal(estimateUsd(9), "$0.05");
  assert.equal(estimateUsd(287), "$1.44");
  assert.equal(estimateUsd(1), "$0.01");
});

test("0 件なら $0.00。負や NaN でも表示を壊さない", () => {
  assert.equal(estimateUsd(0), "$0.00");
  assert.equal(estimateUsd(-1), "$0.00");
  assert.equal(estimateUsd(Number.NaN), "$0.00");
});

test("想定上限は月 $5（NFR-04）", () => {
  assert.equal(MONTHLY_BUDGET_USD, 5);
});

test("ちょうど $5 は超過ではない。要件は「$5 を超えない」", () => {
  assert.equal(overBudget(1000), false, "1000 リソース = $5.00");
  assert.equal(overBudget(1001), true, "1 リソース超えれば超過");
});

test("通常運用の想定（月 300 前後）では超過にならない", () => {
  assert.equal(overBudget(300), false);
  assert.equal(overBudget(0), false);
});
