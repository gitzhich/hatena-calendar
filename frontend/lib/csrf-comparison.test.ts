import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

/**
 * CSRF トークンの比較が固定時間であることの検査。
 *
 * **これはソースを読む検査で、振る舞いのテストではない。**
 * `a === b` と定数時間ループは同じ真偽値を返す。違うのは
 * 「不一致で打ち切るか」という所要時間だけで、機能テストからは観測できない。
 * 実際、比較を === に戻す変異は他のテストをすべて素通りした。
 *
 * バックエンドの API キー比較にも同じ検査を置いている
 * （ApiKeyComparisonTest）。観測できない規約は、規約のまま放置すると
 * 黙って破られる。
 */
describe("csrfMatches の比較方式", () => {
  const source = readFileSync(new URL("./session.ts", import.meta.url), "utf8");
  // コメントを落としてから見る。説明文に === と書けるようにするため
  const code = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
  const body = code.slice(code.indexOf("export function csrfMatches"));

  it("XOR で全文字を走査している", () => {
    assert.match(body, /\^/, "文字ごとの XOR で差分を積む実装であること");
    assert.match(body, /diff \|=/, "途中で return せず差分を積むこと");
  });

  it("トークン同士を === や == で直接比べていない", () => {
    assert.doesNotMatch(
      body.replace(/provided\.length !== expected\.length/, ""),
      /expected\s*={2,3}\s*provided|provided\s*={2,3}\s*expected/,
      "早期 return すると応答時間の差からトークンを推測されうる",
    );
  });
});
