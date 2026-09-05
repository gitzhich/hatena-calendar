-- XINXIN 出演情報カレンダー 初期スキーマ
--
-- 正本は docs/data-model.md「テーブル定義」。**このファイルを後から編集しない**
-- （Flyway がチェックサム不一致で起動を止める）。変更は新しい番号で追加する。
-- 設計の根拠は各テーブルの節と docs/adr/ を参照。

CREATE TABLE source_account (
    id                    BIGSERIAL   PRIMARY KEY,
    username              TEXT        NOT NULL UNIQUE,
    x_user_id             BIGINT      NOT NULL UNIQUE,
    last_fetched_tweet_id BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ingested_post (
    id                BIGSERIAL   PRIMARY KEY,
    source_account_id BIGINT      NOT NULL REFERENCES source_account (id),
    tweet_id          BIGINT      NOT NULL UNIQUE,
    posted_at         TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL
                                  CHECK (status IN ('REGISTERED', 'UNPARSED', 'EXCLUDED')),
    ingested_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE appearance (
    id                     BIGSERIAL   PRIMARY KEY,
    appearance_date        DATE        NOT NULL,
    event_name             TEXT        NOT NULL CHECK (length(event_name) BETWEEN 1 AND 200),
    event_key              TEXT        NOT NULL CHECK (length(event_key) BETWEEN 1 AND 200),
    venue_name             TEXT        CHECK (venue_name IS NULL OR length(venue_name) <= 300),
    performance_start_time TIME,
    performance_end_time   TIME,
    merch_start_time       TIME,
    merch_end_time         TIME,
    ticket_url             TEXT        CHECK (ticket_url IS NULL OR ticket_url ~ '^https?://'),
    source_url             TEXT        NOT NULL CHECK (source_url ~ '^https://'),
    source_type            TEXT        NOT NULL CHECK (source_type IN ('AUTO', 'MANUAL')),
    ingested_post_id       BIGINT      REFERENCES ingested_post (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT appearance_auto_requires_post
        CHECK ((source_type = 'AUTO' AND ingested_post_id IS NOT NULL)
            OR (source_type = 'MANUAL')),

    CONSTRAINT appearance_performance_time_order
        CHECK (performance_start_time IS NULL
            OR performance_end_time IS NULL
            OR performance_start_time <= performance_end_time),

    CONSTRAINT appearance_merch_time_order
        CHECK (merch_start_time IS NULL
            OR merch_end_time IS NULL
            OR merch_start_time <= merch_end_time),

    CONSTRAINT appearance_unique_event
        UNIQUE NULLS NOT DISTINCT
            (appearance_date, event_key, performance_start_time)
);

CREATE TABLE ingestion_run (
    id                     BIGSERIAL   PRIMARY KEY,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    status                 TEXT        NOT NULL
                                       CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    fetched_resource_count INTEGER     NOT NULL DEFAULT 0 CHECK (fetched_resource_count >= 0),
    new_appearance_count   INTEGER     NOT NULL DEFAULT 0 CHECK (new_appearance_count >= 0),
    error_summary          TEXT        CHECK (error_summary IS NULL OR length(error_summary) <= 500)
);

-- 自動登録された出演情報の点検一覧（FR-24）
CREATE INDEX idx_appearance_source_type_created
    ON appearance (source_type, created_at DESC);

-- 未処理投稿の一覧（FR-25）
CREATE INDEX idx_ingested_post_status_posted
    ON ingested_post (status, posted_at DESC);

-- 最終成功日時の取得（FR-08）
CREATE INDEX idx_ingestion_run_status_finished
    ON ingestion_run (status, finished_at DESC);
