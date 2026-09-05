-- 打ち切り（連続 10 回失敗）から戻すための状態を足す。
-- docs/runbook-x-api-setup.md 第 9 章 / docs/x-integration.md 第 7 章。
--
-- 打ち切られると新しい ingestion_run が作られないため、直近 10 件は永久に
-- FAILED のままになる。戻す手段が SUCCESS への書き換え（走っていない実行を
-- 成功と記録する）か DELETE（記録が消える）しかなく、どちらも記録を歪める。
--
-- CANCELLED は「管理者が原因を確認し、打ち切りカウントから外した実行」を表す。
-- IngestionHaltRule は FAILED 以外で連続を切るため、判定側の変更は要らない。
ALTER TABLE ingestion_run
    DROP CONSTRAINT ingestion_run_status_check;

ALTER TABLE ingestion_run
    ADD CONSTRAINT ingestion_run_status_check
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'));
