-- 実行ごとの未処理投稿の件数（FR-42 / docs/data-model.md「ingestion_run — 取り込み実行ログ」）。
--
-- NOT NULL DEFAULT 0 にしない。既存の行は実際の件数が分からず、0 を入れると
-- 「未処理は無かった」と読めてしまう。NULL のままにして画面で「—」と出す。
ALTER TABLE ingestion_run ADD COLUMN unparsed_count INTEGER
    CHECK (unparsed_count IS NULL OR unparsed_count >= 0);
