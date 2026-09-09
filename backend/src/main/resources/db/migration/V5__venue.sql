-- 会場の正規化（docs/data-model.md「venue — 会場」/ ADR-0022）。
--
-- **データの移行はここで行わない。** venue_key の正規化と地域の判定は
-- アプリ側のロジックであり、SQL に書き直すと実装が 2 つになって必ず食い違う。
-- 既存行への venue_id の充填は定期実行が通常運用と同じ経路で行う。
CREATE TABLE venue (
    id                  BIGSERIAL   PRIMARY KEY,
    venue_key           TEXT        NOT NULL UNIQUE
                                    CHECK (length(venue_key) BETWEEN 1 AND 300),
    display_name        TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 300),
    region              TEXT        NOT NULL
                                    CHECK (region IN ('HOKKAIDO', 'TOHOKU', 'KANTO', 'CHUBU',
                                                      'KINKI', 'CHUGOKU', 'SHIKOKU', 'KYUSHU',
                                                      'OVERSEAS', 'UNKNOWN')),
    place_id            TEXT        CHECK (place_id IS NULL OR length(place_id) <= 300),
    place_id_checked_at TIMESTAMPTZ,
    manually_edited     BOOLEAN     NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE appearance ADD COLUMN venue_id BIGINT REFERENCES venue (id);

-- 会場ごとの出演件数（管理画面）と、venue_id が未設定の行の抽出（定期実行）に効く。
-- Postgres は外部キーに索引を自動では作らない。
CREATE INDEX idx_appearance_venue ON appearance (venue_id);
