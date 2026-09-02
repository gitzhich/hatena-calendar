#!/usr/bin/env python3
"""実装をわざと壊して、テストが検出するか確かめる。

なぜスクリプトにしたか
----------------------
この作業は手で回すと **誤りが「成功」に見える**。

変異のあと ``git checkout -- <file>`` で戻す手順を使っていたところ、
修正が未コミットのまま実行して**変異ではなく修正ごと**捨てた。すると
ベースラインが壊れた状態でテストが落ち、ハーネスはそれを「変異を検出した」
と表示する。実際には何も検証できていない。**偽陽性が黙って出る。**

この 3 つを仕組みで守る。

1. 作業ツリーが汚れていたら実行しない（ベースラインが曖昧なまま始めない）
2. 変異前にベースラインが緑であることを確かめる
3. **その変異が使うテスト選択が、変異前に緑であることも確かめる**
4. 復元は git ではなく**メモリに退避した元の中身**から行う

3 が要るのは、テストコマンドやフィルタが間違っていると
「何も選ばれず失敗」がそのまま「変異を検出した」に見えるため。
実際に ``--cwd`` を指定し忘れてフロントのファイルに backend の
コマンドを当て、偽陽性を出した。

使い方
------
仕様を標準入力から渡す。区切りは行頭の ``###`` と ``---``。

    scripts/mutation-test.py <<'EOF'
    ### desc: since_id を送らない
    ### file: backend/src/main/java/.../XApiHttpClient.java
    ### tests: *XApiHttpClientTest*
    --- from
    case FetchWindow.Since since -> b.queryParam("since_id", since.sinceId());
    --- to
    case FetchWindow.Since since -> { }
    EOF

``from`` は対象ファイル内で**ちょうど 1 回**現れなければならない。
0 回でも 2 回以上でも、その変異は失敗として扱う（黙って何もしないのを防ぐ）。

``from`` の代わりに ``### regex:`` で 1 行を指す書き方もできる。
``\u3000`` や ``\t`` のようなエスケープを含む行は、ヒアドキュメントを
通る間に変質しやすい。正規表現で指せばリテラルを写す必要がない。

    ### desc: 空白の許容を外す
    ### file: backend/src/main/java/.../PostParser.java
    ### tests: *PostParser*
    ### regex: private static final String SP = "[^"]*";
    --- to
        private static final String SP = "";

終了コード: 生き残った変異が 1 つでもあれば 1。
"""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CWD = REPO / "backend"
DEFAULT_CMD = "./gradlew test --tests '{tests}' -q"
BASELINE_CMD = "./gradlew test -q"


@dataclasses.dataclass
class Mutation:
    desc: str
    file: pathlib.Path
    tests: str
    new: str
    old: str | None = None
    regex: str | None = None

    def apply(self, source: str) -> tuple[str, int]:
        """変異後の中身と、置換した件数を返す。"""
        if self.regex is not None:
            return re.subn(self.regex, lambda _: self.new, source, count=2)
        return source.replace(self.old, self.new), source.count(self.old)


def parse(text: str) -> list[Mutation]:
    """``###`` の見出しと ``--- from`` / ``--- to`` の本文に分ける。"""
    mutations: list[Mutation] = []
    meta: dict[str, str] = {}
    section: str | None = None
    buf: dict[str, list[str]] = {"from": [], "to": []}

    def flush() -> None:
        if not meta:
            return
        missing = {"desc", "file", "tests"} - meta.keys()
        if missing:
            sys.exit(f"仕様に {sorted(missing)} がありません: {meta}")
        if ("regex" in meta) == bool(buf["from"]):
            sys.exit(f"from と regex はどちらか一方だけ指定します: {meta['desc']}")
        mutations.append(Mutation(
            desc=meta["desc"],
            file=REPO / meta["file"],
            tests=meta["tests"],
            old="\n".join(buf["from"]) if buf["from"] else None,
            regex=meta.get("regex"),
            new="\n".join(buf["to"]),
        ))

    for line in text.splitlines():
        if line.startswith("### "):
            if section is not None:      # 次の変異に入った
                flush()
                meta, buf, section = {}, {"from": [], "to": []}, None
            key, _, value = line[4:].partition(":")
            meta[key.strip()] = value.strip()
        elif line.startswith("--- "):
            section = line[4:].strip()
            if section not in buf:
                sys.exit(f"不明な区切りです: {line}")
        elif section is not None:
            buf[section].append(line)
    flush()
    return mutations


def run(command: str, cwd: pathlib.Path) -> bool:
    done = subprocess.run(command, shell=True, cwd=cwd,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return done.returncode == 0


def require_clean_tree() -> None:
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=REPO,
                           capture_output=True, text=True).stdout.strip()
    if dirty:
        print("作業ツリーが汚れています。変異テストの前にコミットしてください。\n",
              file=sys.stderr)
        print(dirty, file=sys.stderr)
        print("\n未コミットの変更があると、変異と区別がつかなくなります。"
              "ベースラインが壊れた状態は「変異を検出した」と見分けられません。",
              file=sys.stderr)
        sys.exit(2)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cwd", type=pathlib.Path, default=DEFAULT_CWD,
                    help="テストを実行するディレクトリ")
    ap.add_argument("--test-cmd", default=DEFAULT_CMD,
                    help="{tests} を含むコマンド")
    ap.add_argument("--baseline-cmd", default=BASELINE_CMD,
                    help="ベースライン確認に使うコマンド")
    ap.add_argument("--skip-baseline", action="store_true",
                    help="ベースライン確認を飛ばす（推奨しない）")
    args = ap.parse_args()

    require_clean_tree()

    mutations = parse(sys.stdin.read())
    if not mutations:
        sys.exit("変異の仕様がありません。")

    if not args.skip_baseline:
        print("ベースラインを確認中…", flush=True)
        if not run(args.baseline_cmd, args.cwd):
            print("ベースラインが緑ではありません。先にテストを通してください。",
                  file=sys.stderr)
            return 2
        print("ベースライン: 緑\n")

    survived: list[str] = []
    # 同じテスト選択を何度も確かめない
    selection_ok: dict[str, bool] = {}

    for m in mutations:
        if not m.file.exists():
            print(f"  !! ファイルが無い: {m.file}")
            survived.append(m.desc)
            continue

        command = args.test_cmd.format(tests=m.tests)
        if command not in selection_ok:
            selection_ok[command] = run(command, args.cwd)
        if not selection_ok[command]:
            # 変異前から落ちるなら、落ちた理由は変異ではない。
            # ここを見ないと「テストコマンドが壊れている」を
            # 「変異を検出した」と読み違える
            print(f"  !! 変異前からテストが通らない（コマンドかフィルタを確認）: {m.desc}")
            survived.append(m.desc)
            continue

        original = m.file.read_text(encoding="utf-8")
        mutated, hits = m.apply(original)
        if hits != 1:
            # 0 回だと「変異したつもりで素通り」になる。2 回以上は意図が曖昧
            print(f"  !! 置換対象が {hits} 件（1 件であること）: {m.desc}")
            survived.append(m.desc)
            continue

        try:
            m.file.write_text(mutated, encoding="utf-8")
            caught = not run(args.test_cmd.format(tests=m.tests), args.cwd)
        finally:
            # git ではなく退避した中身から戻す。未コミットの変更を巻き込まない
            m.file.write_text(original, encoding="utf-8")

        print(f"  {'✓ 検出:  ' if caught else '✗ 素通り:'} {m.desc}")
        if not caught:
            survived.append(m.desc)

    print()
    if survived:
        print(f"生き残った変異 {len(survived)} 件。テストがこの壊れ方を検出できません。")
        for desc in survived:
            print(f"  - {desc}")
        return 1
    print(f"{len(mutations)} 件すべて検出しました。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
