-- ページ数の上限で打ち切った実行を記録する（docs/x-integration.md「ページング」、ADR-0020）。
--
-- 打ち切ったとき、未取得のまま残るのは古い側の投稿で、取得位置は最新へ進む。
-- つまり **その区間は二度と取得されない**。これは意図した仕様だが、
-- 起きたことを管理者が知らないと手動登録で補う判断ができない（NFR-09）。
--
-- status には足さない。SUCCESS / FAILED / RUNNING は連続失敗の判定に使われており
-- （IngestionHaltRule）、値を増やすと打ち切りの判定まで巻き込む。
-- 取りこぼしは失敗ではなく、成功した実行に付く注記である。
ALTER TABLE ingestion_run
    ADD COLUMN truncated BOOLEAN NOT NULL DEFAULT false;
