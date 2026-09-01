package dev.mzhin.hatenacal.appearance;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 照合用のイベント名キー（ADR-0006 / docs/data-model.md 第 4.3.2 節）。
 *
 * <p>表示には使わない。画面に出すのは常に告知の原文（event_name）で、
 * 正規化した文字列を表示に回すと元の告知と違う名前がカレンダーに並ぶ。
 *
 * <p>NFKC → 小文字化 → 英数字・日本語文字以外を除去。
 * <b>先頭数文字への切り詰めはしない。</b> 実データで
 * 「LONELY KIDS」と「LONELY NIGHT」が衝突し、同じ日に掛け持ち出演した
 * 別イベントを同一とみなして片方がカレンダーから消えるため。
 */
public final class EventKey {

    /**
     * 残す文字。ひらがな・カタカナ・漢字・英数字と長音符。
     *
     * <p>長音符「ー」(U+30FC) は Unicode の script が Common で、
     * Katakana に含まれない。明示しないと「ニキプレ」が「ニキプレ」のまま
     * でも「ラーメン」が「ラメン」になるような取りこぼしが起きる。
     */
    private static final Pattern DROP =
            Pattern.compile("[^\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}a-z0-9ー]");

    private EventKey() {
    }

    /**
     * @param eventName 告知の原文
     * @return 照合用のキー。正規化結果が空になる場合は原文の小文字化を返す
     *         （event_key は NOT NULL のため）
     */
    public static String of(String eventName) {
        String normalized = Normalizer.normalize(eventName, Normalizer.Form.NFKC)
                .toLowerCase();
        String key = DROP.matcher(normalized).replaceAll("");
        // 記号だけのイベント名では空になる。NOT NULL を守るためのフォールバック
        return key.isEmpty() ? eventName.toLowerCase() : key;
    }
}
