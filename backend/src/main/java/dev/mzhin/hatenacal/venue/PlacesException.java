package dev.mzhin.hatenacal.venue;

/**
 * Places API に到達できない、またはエラーが返った。
 *
 * <p><b>「見つからなかった」はこれではない。</b> 見つからなかったのは正常な結果で、
 * {@link PlacesClient#findPlaceId} が empty を返す。両者を混ぜると、Google の障害中に
 * 「存在しない会場」として試行を記録してしまい、<b>再試行が 7 日先へ飛ぶ</b>
 * （docs/data-model.md「venue — 会場」）。
 */
public class PlacesException extends RuntimeException {

    /** HTTP ステータス。接続失敗・タイムアウトは 0。 */
    private final int status;

    public PlacesException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
