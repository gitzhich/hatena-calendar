#!/usr/bin/env python3
"""設計文書への参照が実在するかを検査する。

参照は**位置ではなく同一性**で書く。章・節番号は文書を並べ替えるだけでずれ、
しかも「番号としては解決するが別の章を指す」形で壊れるため検出できない。
実際に x-integration.md の 11.2 → 11.1 の振り直しで参照が 1 件壊れていた。

検査するのは 3 種類。

1. 見出しテキストによる節の参照
       `docs/x-integration.md「日付」`                        コードから
       `[data-model.md](data-model.md)「タイムゾーンの扱い」`  文書から
       `本書「未決定事項」`                                   同じ文書の中から
2. 番号による参照（`第 5.3 節`）が残っていないか
3. 要件・脅威・ADR の ID（FR-08 / NFR-03 / LR-02 / T-01 / ADR-0020）が実在するか

規約は docs/coding-guidelines.md「設計文書への参照」。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 追跡ファイルを拡張子で絞らない。fly.toml / Dockerfile / compose.yaml にも
# 参照は書かれており、拡張子の許可リストにすると取りこぼす（実際に 6 件あった）。
SKIP_SUFFIX = (".png", ".jpg", ".jpeg", ".gif", ".ico", ".webp", ".pdf",
               ".woff", ".woff2", ".ttf", ".jar", ".zip")
# 投稿本文をそのまま置いてあるだけ（参照は書かれていない）と、
# このリポジトリの設計ではない Claude Code のスキル定義
SKIP_PREFIX = ("docs/x-post-sample/", ".claude/")
# この検査自身。照合パターンそのものを含むので、自分を検査すると必ず落ちる
SKIP_FILES = ("scripts/check-doc-refs.py",)
# シークレットが入りうるファイルは、指摘は出すが**行の中身を出さない**。
# この検査は CI のログに出る。追跡外のファイルは git ls-files に載らないので
# 本来ここへは来ないが、誤ってコミットされたときに値がログへ写る経路を残さない。
SECRET_LIKE = re.compile(
    r"(^|/)\.env"
    r"|\.(pem|key|p8|p12|jks|keystore)$"
    r"|(^|/)(secrets|credentials|service-account[^/]*)\.json$"
    r"|application-(local|secret)")

# 見出しは "### 5.3 日付" の形。番号を落としたテキストが参照キーになる
HEADING = re.compile(r"^#{2,6}\s+(?:[0-9]+(?:\.[0-9]+)*[.．]?\s+)?(.*?)\s*$")
# 直前に文書名が隣接しているものだけを参照とみなす（本文中の「」を拾わない）
HEAD_REF = re.compile(r"([A-Za-z0-9][A-Za-z0-9._-]*\.md)(?:</code>|[)`*])*\s*の?\s*「([^」]*)」")
SELF_REF = re.compile(r"本書「([^」]*)」")
NUM_REF = re.compile(r"第 [0-9]+(?:\.[0-9]+)* [章節]")
ID_REF = re.compile(r"\b((?:FR|NFR|LR|T)-[0-9]+)\b")
ADR_REF = re.compile(r"\bADR-([0-9]{4})\b")
ID_DEF = re.compile(r"^#{2,6}\s+((?:FR|NFR|LR|T)-[0-9]+)\b")
# `第 5.12 節` のようにバッククォートで丸ごと囲まれたものは、参照ではなく
# 「書き方そのもの」の引用（規約文書がそう書く）。参照としては数えない。
# 見出しの中に `DATABASE_URL` のようなコードが含まれることがあるので、
# 「丸ごと囲まれているか」で判定する。一部が外に出ていれば参照とみなす。
CODE_SPAN = re.compile(r"`[^`]*`")
# ``` で囲まれたブロックは書き方の例示。参照としては数えない。
FENCE = re.compile(r"^\s*(```|~~~)")


def tracked_files():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT,
                         capture_output=True, text=True, check=True).stdout
    return [f for f in out.split()
            if not f.endswith(SKIP_SUFFIX)
            and not f.startswith(SKIP_PREFIX)
            and f not in SKIP_FILES]


def load_docs():
    """docs/ 配下と CLAUDE.md の見出しテキスト、要件・脅威 ID を集める。"""
    headings, ids = {}, set()
    targets = [(os.path.join(ROOT, "CLAUDE.md"), "CLAUDE.md")]
    for base, _, names in os.walk(os.path.join(ROOT, "docs")):
        targets += [(os.path.join(base, n), n) for n in names if n.endswith(".md")]
    for path, name in targets:
        texts = set()
        for line in open(path, encoding="utf-8"):
            m = HEADING.match(line)
            if m and m.group(1):
                texts.add(m.group(1))
            m = ID_DEF.match(line)
            if m:
                ids.add(m.group(1))
        headings[name] = texts
    return headings, ids


def adr_numbers():
    d = os.path.join(ROOT, "docs", "adr")
    return {n[:4] for n in os.listdir(d) if re.match(r"^[0-9]{4}-.*\.md$", n)}


def main():
    headings, ids = load_docs()
    adrs = adr_numbers()
    errors = []

    for rel in tracked_files():
        self_doc = os.path.basename(rel) if rel.startswith("docs/") and rel.endswith(".md") else None
        try:
            lines = open(os.path.join(ROOT, rel), encoding="utf-8").read().splitlines()
        except (UnicodeDecodeError, IsADirectoryError):
            continue                      # バイナリ・サブモジュールは読み飛ばす
        redact = bool(SECRET_LIKE.search(rel))
        in_fence = False
        for no, line in enumerate(lines, 1):
            if rel.endswith(".md") and FENCE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            def err(msg):
                body = "（内容は表示しない）" if redact else line.strip()[:110]
                errors.append(f"{rel}:{no}: {msg}\n      {body}")

            spans = [m.span() for m in CODE_SPAN.finditer(line)]

            def quoted(m):
                return any(a <= m.start() and m.end() <= b for a, b in spans)

            for m in NUM_REF.finditer(line):
                if not quoted(m):
                    err(f"番号で参照している（{m.group(0)}）。見出しテキストで書く")

            for m in HEAD_REF.finditer(line):
                if quoted(m):
                    continue
                doc, text = m.group(1), m.group(2)
                if doc not in headings:
                    err(f"参照先の文書が無い: {doc}")
                elif text not in headings[doc]:
                    err(f"{doc} に見出し「{text}」が無い")

            for m in SELF_REF.finditer(line):
                if quoted(m):
                    continue
                text = m.group(1)
                if self_doc is None:
                    err(f"「本書」は docs/ の文書の中でしか使えない（「{text}」）")
                elif text not in headings[self_doc]:
                    err(f"{self_doc} に見出し「{text}」が無い")

            for i in ID_REF.findall(line):
                if i not in ids:
                    err(f"定義されていない ID: {i}")

            for n in ADR_REF.findall(line):
                if n not in adrs:
                    err(f"存在しない ADR: ADR-{n}")

    if errors:
        print(f"設計文書への参照に {len(errors)} 件の問題があります。\n")
        for e in errors:
            print("  " + e)
        print("\n規約は docs/coding-guidelines.md「設計文書への参照」。")
        return 1
    print("設計文書への参照はすべて解決できました。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
