package dev.mzhin.hatenacal.ingestion;

import dev.mzhin.hatenacal.appearance.AppearanceCommand;
import dev.mzhin.hatenacal.appearance.AppearanceService;
import dev.mzhin.hatenacal.appearance.IngestionOutcome;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * X の投稿を取り込んで出演情報にする（FR-40〜FR-43）。
 *
 * <p>設計は docs/x-integration.md 第 2 章。要点は 3 つ。
 *
 * <ul>
 *   <li><b>投稿は古い順に処理する。</b>「公演情報解禁 → タイムテーブル解禁」の順で
 *       反映しないと空欄補完が働かない（第 2.1 節）
 *   <li><b>取得位置は全件処理後に一度だけ進める。</b>途中で失敗したら進めない。
 *       次回同じ範囲を取り直すほうが、位置を進めるより安全（第 2.1 節）
 *   <li><b>連続失敗したら止まる。</b>失敗のたびに同じ範囲を取り直すため、
 *       放置すると 24 時間の重複排除が切れて再課金が積み上がる（第 7 章、FR-43）
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** これを超えて RUNNING のままなら、落ちたとみなして倒す（第 10.1 節）。 */
    static final Duration STALE_RUN_THRESHOLD = Duration.ofMinutes(15);

    private final XApiClient client;
    private final PostParser parser;
    private final AppearanceService appearances;
    private final SourceAccountRepository sourceAccounts;
    private final IngestedPostRepository ingestedPosts;
    private final IngestionRunRepository runs;
    private final XApiProperties properties;
    private final TransactionTemplate tx;
    private final Validator validator;
    private final Clock clock;

    public IngestionService(XApiClient client, PostParser parser,
            AppearanceService appearances, SourceAccountRepository sourceAccounts,
            IngestedPostRepository ingestedPosts, IngestionRunRepository runs,
            XApiProperties properties, TransactionTemplate tx,
            Validator validator, Clock clock) {
        this.client = client;
        this.parser = parser;
        this.appearances = appearances;
        this.sourceAccounts = sourceAccounts;
        this.ingestedPosts = ingestedPosts;
        this.runs = runs;
        this.properties = properties;
        this.tx = tx;
        this.validator = validator;
        this.clock = clock;
    }

    /** 実行結果。呼び出し側とテストが「何が起きたか」を判別できるようにする。 */
    public enum Result {
        /** 取り込んだ（新規投稿が 0 件でも成功）。 */
        COMPLETED,
        /** トークン未設定。取り込みだけを飛ばす。公開カレンダーは動く（NFR-02）。 */
        NOT_CONFIGURED,
        /** 情報源アカウントが DB に無い。 */
        NO_SOURCE_ACCOUNT,
        /** 別の実行が動いている（第 10.1 節）。 */
        ALREADY_RUNNING,
        /** 連続失敗で打ち切られている。管理者の操作を待つ（第 7 章）。 */
        HALTED,
        /** 実行して失敗した。 */
        FAILED
    }

    public Result run() {
        if (!properties.configured()) {
            log.info("X_BEARER_TOKEN が未設定のため取り込みをスキップする");
            return Result.NOT_CONFIGURED;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);

        Boolean blocked = tx.execute(s -> reapStaleRuns(now));
        if (Boolean.TRUE.equals(blocked)) {
            log.info("別の取り込みが実行中のためスキップする");
            return Result.ALREADY_RUNNING;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> halted()))) {
            log.error("取り込みが {} 回連続で失敗しているため停止している。"
                    + "原因を確認してから手で戻すこと", IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);
            return Result.HALTED;
        }

        Optional<SourceAccount> found = tx.execute(s ->
                sourceAccounts.findByUsername(properties.sourceUsername()));
        if (found == null || found.isEmpty()) {
            log.warn("情報源アカウント {} が DB に無いため取り込みをスキップする",
                    properties.sourceUsername());
            return Result.NO_SOURCE_ACCOUNT;
        }
        SourceAccount account = found.get();

        IngestionRun run = tx.execute(s -> runs.save(IngestionRun.start(now)));
        Counters counters = new Counters();
        try {
            List<SourcePost> posts = fetchAll(account, now, counters);
            process(account, posts, counters);
            advance(account, posts);

            Long runId = run.getId();
            tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r ->
                    r.succeed(OffsetDateTime.now(clock),
                            counters.resources, counters.created, counters.truncated)));
            log.info("取り込み完了: 取得 {} 件 / 新規 {} 件 / 未処理 {} 件{}",
                    counters.resources, counters.created, counters.unparsed,
                    counters.truncated ? " / 取りこぼしあり" : "");
            return Result.COMPLETED;
        } catch (RuntimeException e) {
            // 取得位置は進めない。次回同じ範囲を取り直す（第 2.1 節）
            Long runId = run.getId();
            String summary = summarize(e);
            tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r ->
                    r.fail(OffsetDateTime.now(clock), summary, counters.resources)));
            log.error("取り込みに失敗した: {}", summary);
            return Result.FAILED;
        }
    }

    // ------------------------------------------------------------------ 取得

    /**
     * ページングしながら取得する。
     *
     * <p>1 回の実行で取るページ数に上限を設ける（第 3.4 節）。暴走した課金を防ぐため。
     *
     * <p><b>上限に達したら、未取得の古い側は取得を諦める。</b> API は新しい順に返すため、
     * 打ち切ったときに残るのは古い側であり、取得位置を最新へ進めるとその区間は
     * 二度と取得されない。位置を進めないほうが安全に見えるが、滞留が上限を超えている
     * 限り毎回同じ範囲を取り直して<b>位置が永久に進まず</b>、24 時間の重複排除が
     * 日跨ぎで切れて課金が積み上がる（ADR-0020）。
     *
     * <p>諦めたことは {@code ingestion_run.truncated} に残す。管理者が手動登録で
     * 補う判断をするために要る（NFR-09）。
     */
    private List<SourcePost> fetchAll(SourceAccount account, OffsetDateTime now,
            Counters counters) {
        FetchWindow window = window(account, now);
        List<SourcePost> all = new ArrayList<>();
        String token = null;

        for (int page = 0; page < properties.maxPages(); page++) {
            FetchResult result = client.fetchPosts(account.getXUserId(), window, token);
            all.addAll(result.posts());
            counters.resources += result.resourceCount();
            if (!result.hasNextPage()) {
                return all;
            }
            token = result.nextToken();
        }
        counters.truncated = true;
        log.warn("ページ数の上限 {} に達したため打ち切った。"
                + "取得できなかった古い投稿は取り直さない。手動登録で補うこと",
                properties.maxPages());
        return all;
    }

    /**
     * 取得範囲を決める。
     *
     * <p>取得済みの位置があれば差分取得。無ければ初回バックフィルで、
     * 遡る範囲を月数で限定する（第 8 章）。無制限に遡ると課金が読めない。
     */
    private FetchWindow window(SourceAccount account, OffsetDateTime now) {
        Long last = account.getLastFetchedTweetId();
        return last == null
                ? new FetchWindow.From(now.minusMonths(properties.backfillMonths()))
                : new FetchWindow.Since(last);
    }

    // ------------------------------------------------------------------ 処理

    /** <b>古い順に処理する。</b>API は新しい順に返すため、並べ替えてから回す（第 2.1 節）。 */
    private void process(SourceAccount account, List<SourcePost> posts, Counters counters) {
        List<SourcePost> oldestFirst = posts.stream()
                .sorted(Comparator.comparingLong(SourcePost::id))
                .toList();

        for (SourcePost post : oldestFirst) {
            if (Boolean.TRUE.equals(tx.execute(s ->
                    ingestedPosts.existsByTweetId(post.id())))) {
                // 再処理しても出演情報を二重に作らない（FR-41 の冪等性）
                continue;
            }
            ParseResult parsed = parser.parse(post.body(), post.createdAt());
            switch (parsed) {
                case ParseResult.Unparsed unparsed -> {
                    log.debug("投稿 {} を未処理にした: {}", post.id(), unparsed.reason());
                    tx.executeWithoutResult(s -> ingestedPosts.save(IngestedPost.record(
                            account.getId(), post.id(), post.createdAt(),
                            IngestedPostStatus.UNPARSED)));
                    counters.unparsed++;
                }
                case ParseResult.Extracted extracted ->
                        register(account, post, extracted, counters);
            }
        }
    }

    private void register(SourceAccount account, SourcePost post,
            ParseResult.Extracted extracted, Counters counters) {
        /*
         * 出演開始時刻の昇順で処理する（docs/data-model.md 第 7.1 節）。
         * 順序を決めないと同じ入力から違う結果が出る。既存行を引き継ぐのが
         * どの枠かが処理順で入れ替わり、会場の内容が変わる。
         */
        List<ParsedAppearance> slots = extracted.appearances().stream()
                .sorted(Comparator.comparing(ParsedAppearance::performanceStartTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        String sourceUrl = postUrl(account, post);

        /*
         * 判定は投稿単位（docs/x-integration.md 第 5.10 節）。1 枠でも検証に
         * 通らなければ、この投稿からは 1 件も登録せず未処理へ回す。
         *
         * 枠単位で捨てると、抽出できなかった枠がどこにも現れない。投稿は
         * REGISTERED として記録されるため未処理一覧に出ず、取りこぼしが
         * ログだけで消える。単位をパーサ側（投稿単位）に合わせる。
         */
        Optional<String> violation = slots.stream()
                .map(slot -> violation(command(slot, sourceUrl, null)))
                .flatMap(Optional::stream)
                .findFirst();
        if (violation.isPresent()) {
            log.warn("投稿 {} の抽出結果が検証に通らないため未処理にした: {}",
                    post.id(), violation.get());
            tx.executeWithoutResult(s -> ingestedPosts.save(IngestedPost.record(
                    account.getId(), post.id(), post.createdAt(),
                    IngestedPostStatus.UNPARSED)));
            counters.unparsed++;
            return;
        }

        tx.executeWithoutResult(s -> {
            IngestedPost record = ingestedPosts.save(IngestedPost.record(
                    account.getId(), post.id(), post.createdAt(),
                    IngestedPostStatus.REGISTERED));
            for (ParsedAppearance slot : slots) {
                AppearanceCommand cmd = command(slot, sourceUrl, record.getId());
                if (appearances.registerFromIngestion(cmd) == IngestionOutcome.CREATED) {
                    counters.created++;
                }
            }
        });
    }

    private AppearanceCommand command(ParsedAppearance slot, String sourceUrl,
            Long ingestedPostId) {
        return new AppearanceCommand(slot.appearanceDate(), slot.eventName(),
                slot.venueName(), slot.performanceStartTime(), slot.performanceEndTime(),
                slot.merchStartTime(), slot.merchEndTime(), slot.ticketUrl(),
                sourceUrl, ingestedPostId);
    }

    /**
     * サーバ側検証を通す（CLAUDE.md）。
     *
     * <p>X 由来のテキストは信頼しない入力である。抽出が想定外の値を作ったら
     * 登録しない。DB の CHECK 制約で落ちると実行全体が失敗するが、
     * ここで弾けば他の投稿の処理は続く。
     *
     * <p><b>ingestedPostId は検証対象ではない</b>ため、記録を保存する前に呼べる。
     * これにより「登録するか未処理にするか」を書き込みの前に決められる。
     *
     * @return 最初の違反メッセージ。検証を通れば empty
     */
    private Optional<String> violation(AppearanceCommand cmd) {
        return validator.validate(cmd).stream()
                .map(ConstraintViolation::getMessage)
                .findFirst();
    }

    /** 出典 URL（FR-06）。ハンドルの正本は source_account の行。 */
    private static String postUrl(SourceAccount account, SourcePost post) {
        return "https://x.com/%s/status/%d".formatted(account.getUsername(), post.id());
    }

    // ------------------------------------------------------------------ 位置

    /**
     * 取得位置を進める。<b>全件処理したあとに一度だけ</b>（第 2.1 節）。
     *
     * <p>更新は前進するときだけ成立する（{@code advanceLastFetchedTweetId}）。
     * 後退させると同じ投稿を翌日以降に取り直し、再課金になる（第 2.2 節）。
     *
     * <p><b>ページ上限で打ち切った場合も進める</b>（ADR-0020）。進めない実装にすると
     * 滞留が解消せず、課金だけが積み上がる。
     */
    private void advance(SourceAccount account, List<SourcePost> posts) {
        posts.stream().mapToLong(SourcePost::id).max().ifPresent(maxId ->
                tx.executeWithoutResult(s ->
                        sourceAccounts.advanceLastFetchedTweetId(account.getId(), maxId)));
    }

    // ------------------------------------------------------------------ 門番

    /**
     * 多重起動の判定（第 10.1 節）。
     *
     * <p>「終わっていなければスキップ」だけにしない。RUNNING を書いた直後に
     * プロセスが落ちると、その行が残り続けて<b>以降の実行がすべてスキップされ、
     * 取り込みが恒久的に止まる</b>。閾値で打ち切ることで、クラッシュとハングの
     * 両方を 1 つの規則で拾える。
     *
     * @return 実行中の取り込みがあるか（true ならスキップする）
     */
    private boolean reapStaleRuns(OffsetDateTime now) {
        OffsetDateTime deadline = now.minus(STALE_RUN_THRESHOLD);
        boolean blocked = false;
        for (IngestionRun run : runs.findByStatusOrderByStartedAtAsc(
                IngestionRunStatus.RUNNING)) {
            if (run.getStartedAt().isBefore(deadline)) {
                run.fail(now, "%d 分を超えて実行中のままだったため打ち切った"
                        .formatted(STALE_RUN_THRESHOLD.toMinutes()), 0);
            } else {
                blocked = true;
            }
        }
        return blocked;
    }

    /**
     * 連続失敗による打ち切り（第 7 章、FR-43）。
     *
     * <p>失敗するたびに同じ範囲を取り直すため、失敗が UTC の日跨ぎで続くと
     * 24 時間の重複排除が切れて<b>毎日再課金される</b>。
     * ページ上限 10 × 100 件で最悪 $5/日 になりうる。
     *
     * <p><b>自動で再開しない。</b>原因を確認してから手で戻す。
     *
     * <p><b>判定規則そのものは {@link IngestionHaltRule} が持つ。</b>
     * 管理画面の警告（NFR-09）が同じ規則を使うため、ここで書き下ろさない。
     */
    private boolean halted() {
        return IngestionHaltRule.halted(runs.findByOrderByStartedAtDesc(IngestionHaltRule.window()));
    }

    /**
     * 失敗の要約。
     *
     * <p><b>スタックトレースとトークンを含めない</b>（NFR-03）。
     * これは error_summary に入り、管理画面に出る。
     */
    private static String summarize(RuntimeException e) {
        return e instanceof XApiException
                ? e.getMessage()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** 実行中に数える値。 */
    private static final class Counters {
        private int resources;
        private int created;
        /** ページ上限で打ち切ったか（第 3.4 節）。取りこぼしが確定した印。 */
        private boolean truncated;
        private int unparsed;
    }
}
