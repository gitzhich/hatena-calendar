package dev.mzhin.hatenacal.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 会場。docs/data-model.md「venue — 会場」/ ADR-0022。
 *
 * <p><b>表記ゆれをここでまとめる。</b> {@code 大須RADHALL} と {@code 大須RAD HALL} は
 * 同じ {@code venue_key} に寄り、1 行になる。出演情報の {@code venue_name} は
 * 告知の原文のまま残るので、どちらの投稿がどう書いたかは失われない。
 *
 * <p>createdAt / updatedAt は TIMESTAMPTZ で UTC 保存する
 * （docs/data-model.md「タイムゾーンの扱い」）。
 */
@Entity
@Table(name = "venue")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 照合用。<b>画面に出さない。</b> */
    @Column(name = "venue_key", nullable = false, updatable = false)
    private String venueKey;

    /** 代表表記。初めて見た告知の原文が入り、管理者が直せる。 */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false)
    private Region region;

    /**
     * Google の場所 ID。
     *
     * <p><b>null は「まだ同定できていない」。</b> 未解決の間は名前で検索する
     * 地図リンクに落ちるだけで、画面は壊れない。
     */
    @Column(name = "place_id")
    private String placeId;

    /**
     * 最後に解決を試みた日時。<b>成否によらず記録する。</b>
     *
     * <p>記録しないと、Google に存在しない会場を毎日叩き続けることになる。
     */
    @Column(name = "place_id_checked_at")
    private OffsetDateTime placeIdCheckedAt;

    /**
     * 会場ではなく地域だけを表す行か（ADR-0022「会場が未定でも地域は持つ」）。
     *
     * <p>会場が未定の告知が指す {@code 東京} のような行。<b>place_id を解決せず、
     * 地図リンクも出さない。</b>「東京」で地図を検索させると東京駅のような
     * 無関係な場所を指し、誤った地図リンクはリンクが無いより悪い
     * （docs/security.md T-08）。
     */
    @Column(name = "area_only", nullable = false)
    private boolean areaOnly;

    /**
     * 管理者が直したか。
     *
     * <p><b>true の行を自動処理が上書きしない。</b> 人が確認した値のほうが強い
     * （ADR-0022）。
     */
    @Column(name = "manually_edited", nullable = false)
    private boolean manuallyEdited;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Venue() {
        // JPA 用
    }

    /**
     * 新規作成。
     *
     * <p>{@code venueKey} と {@code region} を外から渡す形にしているのは、
     * 生成を {@link VenueService} に閉じ込めるため。event_key と同じ考え方
     * （ADR-0006）で、パッケージ内からしか呼べない。
     */
    static Venue create(String venueKey, String displayName, Region region) {
        Venue v = new Venue();
        v.venueKey = venueKey;
        v.displayName = displayName;
        v.region = region;
        v.manuallyEdited = false;
        v.areaOnly = false;
        return v;
    }

    /**
     * 地域だけの行を作る（ADR-0022「会場が未定でも地域は持つ」）。
     *
     * <p>会場が未定の告知から作られる。<b>place_id は永久に解決しない。</b>
     */
    static Venue createArea(String venueKey, String displayName, Region region) {
        Venue v = create(venueKey, displayName, region);
        v.areaOnly = true;
        return v;
    }

    /**
     * 管理者による訂正（docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>manuallyEdited を立てる。</b> 以後、自動判定も自動解決もこの行を上書きしない。
     * 人が確認した値のほうが強い（ADR-0022）。
     *
     * <p><b>venueKey は変えない。</b> 表記から機械的に決まる値であり、
     * 変えると出演情報が別の会場に化ける（docs/api.md「会場の一覧と編集」）。
     *
     * @param placeId {@code null} で解決前に戻す。誤って解決した場合の取り消し
     */
    void editByAdmin(String displayName, Region region, String placeId) {
        this.displayName = displayName;
        this.region = region;
        this.placeId = placeId;
        this.manuallyEdited = true;
    }

    /**
     * place_id の解決を試みた結果を記録する。
     *
     * <p><b>見つからなくても checkedAt を進める。</b> 記録しないと、Google に存在しない
     * 会場を毎日叩き続けることになる（docs/data-model.md「venue — 会場」）。
     *
     * <p><b>Google に到達できなかったときは呼ばない。</b> 試せていないのに記録すると、
     * 障害が明けても再試行が 7 日先へ飛ぶ（{@link PlacesException}）。
     *
     * @param placeId 見つかった place_id。見つからなければ {@code null}。
     *                <b>null で既存の値を消さない</b>
     */
    void recordPlaceIdAttempt(String placeId, OffsetDateTime checkedAt) {
        if (placeId != null) {
            this.placeId = placeId;
        }
        this.placeIdCheckedAt = checkedAt;
    }

    public Long getId() {
        return id;
    }

    public String getVenueKey() {
        return venueKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Region getRegion() {
        return region;
    }

    public String getPlaceId() {
        return placeId;
    }

    public OffsetDateTime getPlaceIdCheckedAt() {
        return placeIdCheckedAt;
    }

    public boolean isAreaOnly() {
        return areaOnly;
    }

    public boolean isManuallyEdited() {
        return manuallyEdited;
    }
}
