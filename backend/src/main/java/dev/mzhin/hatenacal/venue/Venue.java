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
        return v;
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

    public boolean isManuallyEdited() {
        return manuallyEdited;
    }
}
