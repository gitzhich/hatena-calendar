package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.common.BadRequestException;
import dev.mzhin.hatenacal.common.ConflictException;
import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.ingestion.IngestedPost;
import dev.mzhin.hatenacal.ingestion.IngestedPostRepository;
import dev.mzhin.hatenacal.ingestion.IngestedPostStatus;
import dev.mzhin.hatenacal.venue.Venue;
import dev.mzhin.hatenacal.venue.VenueService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出演情報の読み取り。
 *
 * <p>認可はコントローラ層だけでなくこの層でも検証する方針だが（NFR-03）、
 * 公開 API は読み取り専用で誰でも見られるデータのため、ここでは入力検証のみ行う。
 */
@Service
public class AppearanceService {

    private final AppearanceRepository repository;
    private final IngestedPostRepository ingestedPostRepository;
    private final VenueService venues;
    private final Clock clock;

    public AppearanceService(AppearanceRepository repository,
            IngestedPostRepository ingestedPostRepository, VenueService venues, Clock clock) {
        this.repository = repository;
        this.ingestedPostRepository = ingestedPostRepository;
        this.venues = venues;
        this.clock = clock;
    }

    /**
     * 会場の紐づけを venue_name に合わせ直す（ADR-0022）。
     *
     * <p><b>書き込みのたびに呼ぶ。</b> 会場名が変わったのに venue_id が古いままだと、
     * 表示している会場と地図・色が食い違う。毎回引き直すので自己修復もする。
     */
    private void syncVenue(Appearance target) {
        Venue venue = venues.findOrCreate(target.getVenueName());
        target.linkVenue(venue == null ? null : venue.getId());
    }

    /** DTO 化のために会場をまとめて引く。会場ごとに引くと N+1 になる。 */
    private Map<Long, Venue> venuesOf(List<Appearance> rows) {
        return venues.byIds(rows.stream().map(Appearance::getVenueId).toList());
    }

    /**
     * {@code venues} から会場を取り出す。
     *
     * <p><b>venue_id が null のときはマップを引かない。</b>
     * {@code Map.of()} は null キーの {@code get} で例外を投げる。
     */
    private static Venue venueOf(Map<Long, Venue> venues, Appearance a) {
        return a.getVenueId() == null ? null : venues.get(a.getVenueId());
    }

    /**
     * 会場の紐づけが済んでいない行を埋める（ADR-0022「既存データの初期投入」）。
     *
     * <p><b>初期投入と通常運用が同じ経路になる。</b> 専用の移行コードを持たない。
     * 冪等で、途中で落ちても次回が続きから拾う。
     *
     * @param limit 1 回で処理する上限
     * @return 埋めた件数
     */
    @Transactional(readOnly = true)
    public long countMissingVenues() {
        return repository.countNeedingVenueLink();
    }

    @Transactional
    public int linkMissingVenues(int limit) {
        List<Appearance> targets = repository.findNeedingVenueLink(PageRequest.of(0, limit));
        targets.forEach(this::syncVenue);
        return targets.size();
    }

    /**
     * 期間内の出演情報を返す。
     *
     * <p><b>検証を通ってから DB を引く。</b> 範囲外の年月で DB へ到達させないことが
     * T-04（無料枠の枯渇による可用性攻撃）の防御になる。
     */
    @Transactional(readOnly = true)
    public List<PublicAppearanceDto> findForCalendar(LocalDate from, LocalDate to) {
        validate(from, to);
        List<Appearance> rows = repository.findForCalendar(from, to);
        Map<Long, Venue> loaded = venuesOf(rows);
        return rows.stream().map(a -> PublicAppearanceDto.from(a, venueOf(loaded, a))).toList();
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadRequestException("from は to 以前である必要があります");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > CalendarRange.MAX_SPAN_DAYS) {
            throw new BadRequestException(
                    "期間は %d 日以内である必要があります".formatted(CalendarRange.MAX_SPAN_DAYS));
        }
        // FR-05 / ADR-0014：期間の長さとは別に、月の種類数を縛る
        if (!CalendarRange.contains(from, clock) || !CalendarRange.contains(to, clock)) {
            throw new BadRequestException("表示できる期間は %s から %s までです"
                    .formatted(CalendarRange.firstDate(), CalendarRange.lastDate(clock)));
        }
    }

    // ------------------------------------------------------------------
    // 管理操作（FR-21 〜 FR-24）
    // ------------------------------------------------------------------

    /**
     * 点検一覧（FR-24）。sourceType 未指定なら全件。
     *
     * <p>並び順は {@code pageable} に載って来る。既定と選べる値は
     * {@link AppearanceSort}。
     */
    @Transactional(readOnly = true)
    public Page<AdminAppearanceDto> findForReview(SourceType sourceType, Pageable pageable) {
        Page<Appearance> page = sourceType == null
                ? repository.findAll(pageable)
                : repository.findBySourceType(sourceType, pageable);
        Map<Long, Venue> loaded = venuesOf(page.getContent());
        return page.map(a -> AdminAppearanceDto.from(a, venueOf(loaded, a)));
    }

    @Transactional(readOnly = true)
    public AdminAppearanceDto findById(Long id) {
        Appearance target = load(id);
        return AdminAppearanceDto.from(target, venueOf(venuesOf(List.of(target)), target));
    }

    /**
     * 手動登録（FR-21）。
     *
     * <p>既存があれば上書きせず 409 を返す。修正は編集（FR-22）で行う。
     * 黙って上書きすると、管理者が直した内容が別の登録操作で消える。
     */
    @Transactional
    public AdminAppearanceDto create(AppearanceCommand cmd) {
        validateTimes(cmd);
        String key = EventKey.of(cmd.eventName());
        requireNoConflict(cmd.appearanceDate(), key, cmd.performanceStartTime(), null);

        IngestedPost post = resolveIngestedPost(cmd.ingestedPostId());
        Appearance saved = repository.save(Appearance.create(
                key,
                // 抽出元を指定していても、作ったのは管理者なので MANUAL。
                // AUTO は取り込みジョブだけが付ける
                SourceType.MANUAL,
                cmd.appearanceDate(), cmd.eventName(), cmd.venueName(),
                cmd.performanceStartTime(), cmd.performanceEndTime(),
                cmd.merchStartTime(), cmd.merchEndTime(),
                cmd.ticketUrl(), cmd.sourceUrl(),
                post == null ? null : post.getId()));

        // 処理済みの投稿を未処理一覧から外す（docs/api.md「手動登録」）。
        // これがないと登録しても一覧に残り続ける
        if (post != null) {
            post.markRegistered();
        }
        syncVenue(saved);
        return AdminAppearanceDto.from(saved, venueOf(venuesOf(List.of(saved)), saved));
    }

    /**
     * 自動取り込みからの登録・補完（FR-41）。
     *
     * <p>照合と補完の規則は docs/data-model.md「追加告知による空欄補完」。
     *
     * <ol>
     *   <li>日付・イベント・開始時刻が<b>すべて一致</b>する行があれば、その空欄を埋める
     *   <li>無ければ、同じ日付・イベントで<b>開始時刻が NULL</b> の行を探す。
     *       あればそこへ時刻を書き込む（時刻なしで登録された行に、
     *       後続の「タイムテーブル解禁」が時刻を入れる流れ）
     *   <li>登録しようとしているのが<b>時刻なし</b>で、同じ日付・イベントの行が
     *       既にあるなら何もしない。一意キーは開始時刻を含むため、
     *       時刻ありの行があっても時刻なしの行は作れてしまい、
     *       カレンダーに同じ公演が 2 行並ぶ
     *   <li>どれにも当てはまらなければ新規登録する
     * </ol>
     *
     * <p>手順 2 は手順 1 が空振りしたときにだけ走るため、
     * 埋めた結果が既存行と衝突することはない。
     *
     * <p><b>承認を挟まずそのまま公開する</b>（FR-41）。告知の速報性を優先し、
     * 誤りは管理者の事後修正で直す。
     */
    @Transactional
    public IngestionOutcome registerFromIngestion(AppearanceCommand cmd) {
        validateTimes(cmd);
        String key = EventKey.of(cmd.eventName());

        Optional<Appearance> exact = cmd.performanceStartTime() == null
                ? repository.findByAppearanceDateAndEventKeyAndPerformanceStartTimeIsNull(
                        cmd.appearanceDate(), key)
                : repository.findByAppearanceDateAndEventKeyAndPerformanceStartTime(
                        cmd.appearanceDate(), key, cmd.performanceStartTime());
        if (exact.isPresent()) {
            if (!fillBlanks(exact.get(), cmd)) {
                return IngestionOutcome.UNCHANGED;
            }
            // 会場が空欄だった行に会場が入ることがある（ADR-0021 の告知に
            // タイムテーブルが続く流れ）。紐づけをその場で合わせる
            syncVenue(exact.get());
            return IngestionOutcome.COMPLETED;
        }

        if (cmd.performanceStartTime() != null) {
            Optional<Appearance> timeless = repository
                    .findByAppearanceDateAndEventKeyAndPerformanceStartTimeIsNull(
                            cmd.appearanceDate(), key);
            if (timeless.isPresent()) {
                fillBlanks(timeless.get(), cmd);
                syncVenue(timeless.get());
                return IngestionOutcome.COMPLETED;
            }
        } else if (repository.existsByAppearanceDateAndEventKey(cmd.appearanceDate(), key)) {
            // タイムテーブル未確定の告知が、既に時刻付きで登録済みの公演に届いた場合
            // （ADR-0021）。足せる情報が無いので行を増やさない。どの枠の空欄を
            // 埋めるべきかは決まらないため、補完もしない
            return IngestionOutcome.UNCHANGED;
        }

        syncVenue(repository.save(Appearance.create(key, SourceType.AUTO,
                cmd.appearanceDate(), cmd.eventName(), cmd.venueName(),
                cmd.performanceStartTime(), cmd.performanceEndTime(),
                cmd.merchStartTime(), cmd.merchEndTime(),
                cmd.ticketUrl(), cmd.sourceUrl(), cmd.ingestedPostId())));
        return IngestionOutcome.CREATED;
    }

    private static boolean fillBlanks(Appearance target, AppearanceCommand cmd) {
        return target.fillBlanks(cmd.venueName(),
                cmd.performanceStartTime(), cmd.performanceEndTime(),
                cmd.merchStartTime(), cmd.merchEndTime(),
                cmd.ticketUrl(), cmd.sourceUrl(), cmd.ingestedPostId());
    }

    /** 編集（FR-22）。部分更新ではなく全項目を差し替える。 */
    @Transactional
    public AdminAppearanceDto update(Long id, AppearanceCommand cmd) {
        validateTimes(cmd);
        Appearance target = load(id);
        String key = EventKey.of(cmd.eventName());
        requireNoConflict(cmd.appearanceDate(), key, cmd.performanceStartTime(), id);

        target.replace(key, cmd.appearanceDate(), cmd.eventName(), cmd.venueName(),
                cmd.performanceStartTime(), cmd.performanceEndTime(),
                cmd.merchStartTime(), cmd.merchEndTime(),
                cmd.ticketUrl(), cmd.sourceUrl());
        syncVenue(target);
        return AdminAppearanceDto.from(target, venueOf(venuesOf(List.of(target)), target));
    }

    /**
     * 削除（FR-23）。
     *
     * <p>物理削除する。ingested_post の記録は消さないため、同じ投稿から
     * 再登録されることはない（docs/data-model.md「削除と冪等性」）。
     */
    @Transactional
    public void delete(Long id) {
        repository.delete(load(id));
    }

    // ------------------------------------------------------------------

    private Appearance load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("出演情報が見つかりません"));
    }

    /**
     * 一意キーの衝突を検出する（ADR-0012）。
     *
     * <p>DB の UNIQUE 制約に任せず先に見るのは、409 として意味のある応答を
     * 返すため。制約は最後の砦として残す。
     */
    private void requireNoConflict(LocalDate date, String eventKey,
            java.time.LocalTime start, Long selfId) {
        Optional<Appearance> existing = start == null
                ? repository.findByAppearanceDateAndEventKeyAndPerformanceStartTimeIsNull(
                        date, eventKey)
                : repository.findByAppearanceDateAndEventKeyAndPerformanceStartTime(
                        date, eventKey, start);
        if (existing.isPresent() && !existing.get().getId().equals(selfId)) {
            throw new ConflictException(start == null
                    ? "同じ日・同じイベントで出演開始時刻のない出演情報が既にあります"
                    : "同じ日・同じイベント・同じ出演開始時刻の出演情報が既にあります");
        }
    }

    private IngestedPost resolveIngestedPost(Long id) {
        if (id == null) {
            return null;
        }
        IngestedPost post = ingestedPostRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("指定された投稿が見つかりません"));
        if (post.getStatus() == IngestedPostStatus.EXCLUDED) {
            throw new BadRequestException("対象外にした投稿は指定できません");
        }
        return post;
    }

    /** DB の CHECK 制約と同じ条件をサーバ側でも見る（NFR-03）。 */
    private static void validateTimes(AppearanceCommand cmd) {
        if (cmd.performanceStartTime() != null && cmd.performanceEndTime() != null
                && cmd.performanceStartTime().isAfter(cmd.performanceEndTime())) {
            throw new BadRequestException("出演の終了時刻が開始時刻より前です");
        }
        if (cmd.merchStartTime() != null && cmd.merchEndTime() != null
                && cmd.merchStartTime().isAfter(cmd.merchEndTime())) {
            throw new BadRequestException("物販の終了時刻が開始時刻より前です");
        }
        // 出演時刻と物販時刻の前後は問わない。並行物販は出演より前に始まりうる
        // （docs/data-model.md「実際の告知投稿との対応」）
    }
}
