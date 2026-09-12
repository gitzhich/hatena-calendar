#!/usr/bin/env python3
"""配色トークンのコントラスト比を検査する（NFR-08 / FR-10）。

なぜスクリプトにしたか
----------------------
2026-09-06 の DoD 判定では 34 ペアを**手で計算**して 4.5:1 を確認した。
地域ごとの色（[ADR-0022](../docs/adr/0022-venue-place-id-and-region.md)
「地域はチップの色で示し、凡例と絞り込みを添える」）で**ペアが 20 増える**。
手計算では追随できず、**足りない色を足しても誰も気づかない**。

何を検査するか
--------------
`frontend/app/globals.css` の ``:root`` と、ダークの ``@media`` ブロックを読む。

1. **対になっているトークン**（``--chip-1`` と ``--chip-1-ink``、
   ``--region-kanto`` と ``--region-kanto-ink``）を自動で見つけて検査する。
   **一覧を書き並べない。** 書くと、色を足したときに検査だけ漏れる
2. 下の SEMANTIC に書いた、文字と地の組（``ink`` を ``surface`` の上に置く、など）

``-ink`` が付くトークンに相方が無ければ失敗させる。綴り違いを見逃さないため。

閾値は **4.5:1**（NFR-08 / WCAG 2.2 達成基準 1.4.3）。カレンダーのチップは
9px で「大きな文字」の例外に当たらないため、緩めない。

終了コード: 1 つでも下回れば 1。
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSS = os.path.join(ROOT, "frontend", "app", "globals.css")

MIN_RATIO = 4.5

# 文字と地の組。画面で実際に重なるものだけを書く。
# （自動で見つかるのは `--X` と `--X-ink` の対だけなので、それ以外をここで補う）
SEMANTIC = [
    ("foreground", "background", "本文（管理画面の地）"),
    ("ink", "canvas", "本文（公開ページの地）"),
    ("ink", "surface", "本文（カード・シートの上）"),
    ("muted", "canvas", "補足テキスト（公開ページの地）"),
    ("muted", "surface", "補足テキスト（カードの上）"),
    ("accent", "canvas", "リンク（公開ページの地）"),
    ("accent", "surface", "リンク（カードの上）"),
    ("holiday", "canvas", "日曜の曜日見出し"),
    ("saturday", "canvas", "土曜の曜日見出し"),
    ("warn-ink", "warn-bg", "警告"),
]

HEX = re.compile(r"--([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;")


def block(text: str, start: int) -> str:
    """`{` の位置から対応する `}` までを返す。"""
    depth, i = 0, start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i]
        i += 1
    sys.exit("globals.css の波括弧が閉じていない")


def tokens(text: str) -> dict[str, str]:
    return {name: value for name, value in HEX.findall(text)}


def palettes(css: str) -> tuple[dict[str, str], dict[str, str]]:
    """ライトと、ライトにダークを重ねたもの。"""
    light_at = css.index(":root")
    light = tokens(block(css, css.index("{", light_at)))

    dark_at = css.find("prefers-color-scheme: dark")
    if dark_at == -1:
        sys.exit("ダークテーマのブロックが見つからない")
    media = block(css, css.index("{", dark_at))
    dark = dict(light)
    dark.update(tokens(block(media, media.index("{", media.index(":root")))))
    return light, dark


def luminance(value: str) -> float:
    h = value.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    parts = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    parts = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in parts]
    return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2]


def ratio(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def pairs_for(palette: dict[str, str]) -> list[tuple[str, str, str]]:
    """検査する組。`--X-ink` から相方を自動で見つけ、SEMANTIC を足す。

    地は `--X` か `--X-bg`。`--warn-ink` の相方が `--warn-bg` であるように、
    実際に両方の綴りが使われている。
    """
    found = []
    for name in sorted(palette):
        if not name.endswith("-ink"):
            continue
        stem = name[: -len("-ink")]
        base = next((c for c in (stem, f"{stem}-bg") if c in palette), None)
        if base is None:
            sys.exit(f"--{name} に対応する --{stem} も --{stem}-bg も無い（綴り違い？）")
        found.append((name, base, "文字と地（対になっているトークン）"))

    seen = {(fg, bg) for fg, bg, _ in found}
    return found + [p for p in SEMANTIC if (p[0], p[1]) not in seen]


def check(theme: str, palette: dict[str, str]) -> list[str]:
    failures = []
    print(f"\n[{theme}]")
    for fg, bg, why in pairs_for(palette):
        missing = [n for n in (fg, bg) if n not in palette]
        if missing:
            sys.exit(f"{theme}: トークンが無い: {', '.join('--' + m for m in missing)}")
        r = ratio(palette[fg], palette[bg])
        mark = "  " if r >= MIN_RATIO else "NG"
        print(f"  {mark} {r:5.2f}:1  --{fg} / --{bg}  （{why}）")
        if r < MIN_RATIO:
            failures.append(f"{theme}: --{fg} / --{bg} が {r:.2f}:1（{MIN_RATIO} 未満）")
    return failures


def main() -> int:
    with open(CSS, encoding="utf-8") as f:
        css = f.read()
    light, dark = palettes(css)
    failures = check("ライト", light) + check("ダーク", dark)
    if failures:
        print("\n配色のコントラスト比が足りません。", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"\nすべて {MIN_RATIO}:1 以上です。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
