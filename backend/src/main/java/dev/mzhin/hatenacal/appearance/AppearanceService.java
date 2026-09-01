package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.common.BadRequestException;
import dev.mzhin.hatenacal.common.ConflictException;
import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.ingestion.IngestedPost;
import dev.mzhin.hatenacal.ingestion.IngestedPostRepository;
import dev.mzhin.hatenacal.ingestion.IngestedPostStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
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
    private final Clock clock;

    public AppearanceService(AppearanceRepository repository,
            IngestedPostRepository ingestedPostRepository, Clock clock) {
        this.repository = repository;
        this.ingestedPostRepository = ingestedPostRepository;
        this.clock = clock;
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
        return repository.findForCalendar(from, to).stream().map(PublicAppearanceDto::from).toList();
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

    /** 点検一覧（FR-24）。sourceType 未指定なら全件。 */
    @Transactional(readOnly = true)
    public Page<AdminAppearanceDto> findForReview(SourceType sourceType, Pageable pageable) {
        Page<Appearance> page = sourceType == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findBySourceTypeOrderByCreatedAtDesc(sourceType, pageable);
        return page.map(AdminAppearanceDto::from);
    }

    @Transactional(readOnly = true)
    public AdminAppearanceDto findById(Long id) {
        return AdminAppearanceDto.from(load(id));
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

        // 処理済みの投稿を未処理一覧から外す（docs/api.md 第 5.2 節）。
        // これがないと登録しても一覧に残り続ける
        if (post != null) {
            post.markRegistered();
        }
        return AdminAppearanceDto.from(saved);
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
        return AdminAppearanceDto.from(target);
    }

    /**
     * 削除（FR-23）。
     *
     * <p>物理削除する。ingested_post の記録は消さないため、同じ投稿から
     * 再登録されることはない（docs/data-model.md 第 7.2 節）。
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
        // （docs/data-model.md 第 4.3.1 節）
    }
}
