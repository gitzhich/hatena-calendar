-- 会場が未定でも地域を持てるようにする（ADR-0022「会場が未定でも地域は持つ」）。
--
-- 会場が並ぶ告知は venue_name を空欄で登録するため、地域まで失われていた。
-- 東京の公演なのにカレンダーの色が付かない。
--
-- **region の列は足さない。** 地域の置き場所は venue.region のまま 1 か所に保つ。
-- area_name は venue_name と同じ「引き当ての入力」であり、地域そのものではない。

ALTER TABLE appearance
    ADD COLUMN area_name TEXT
        CHECK (area_name IS NULL OR length(area_name) <= 100);

-- 会場ではなく地域だけを表す行。place_id を解決せず、地図リンクも出さない。
-- 「東京」で地図を検索させると東京駅のような無関係な場所を指す（security.md T-08）
ALTER TABLE venue
    ADD COLUMN area_only BOOLEAN NOT NULL DEFAULT false;
