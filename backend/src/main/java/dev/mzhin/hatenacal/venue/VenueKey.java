package dev.mzhin.hatenacal.venue;

import dev.mzhin.hatenacal.appearance.EventKey;

/**
 * 照合用の会場名キー（docs/data-model.md「venue_key の生成規則」/ ADR-0022）。
 *
 * <p>表示には使わない。画面に出すのは告知の原文（{@code appearance.venue_name}）か
 * 会場の代表表記（{@code venue.display_name}）。
 *
 * <p><b>正規化そのものを書き直さない。</b> {@link EventKey} と規則を共有する。
 * 同じ流儀を 2 つ持つと片方だけ直す事故が起き、照合が静かに壊れる（ADR-0022）。
 * 概念が違うので入口は分けてあり、将来どうしても分岐が要るときはここで分かれる。
 *
 * <p>この規則により、実データの表記ゆれが同じキーに寄る。
 *
 * <pre>
 * 愛知・大須RADHALL   → 愛知大須radhall
 * 愛知・大須RAD HALL  → 愛知大須radhall
 * </pre>
 */
public final class VenueKey {

    private VenueKey() {
    }

    public static String of(String venueName) {
        return EventKey.of(venueName);
    }
}
