package dev.mzhin.hatenacal.ingestion;

import java.util.List;

/**
 * 抽出の結果。
 *
 * <p>成功か「未処理」かの 2 値にする。中途半端に埋まった出演情報を作らない
 * （docs/x-integration.md「抽出対象の判定」）。未処理は破棄されず管理者に回る（FR-25）。
 */
public sealed interface ParseResult {

    /** 抽出できた。1 投稿から複数件になることがある（docs/x-integration.md「1 投稿から複数の出演情報」）。 */
    record Extracted(List<ParsedAppearance> appearances) implements ParseResult {
    }

    /**
     * 抽出しない。reason は管理画面での仕分けとテストのための説明で、
     * 閲覧者には出さない。
     */
    record Unparsed(String reason) implements ParseResult {
    }

    static ParseResult unparsed(String reason) {
        return new Unparsed(reason);
    }
}
