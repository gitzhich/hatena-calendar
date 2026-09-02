package dev.mzhin.hatenacal.ingestion;

/**
 * X API の呼び出しに失敗した。
 *
 * <p><b>メッセージに Bearer Token を含めない</b>（NFR-03）。
 * このメッセージは {@code ingestion_run.error_summary} に入り、管理画面に出る。
 */
public class XApiException extends RuntimeException {

    private final int statusCode;

    public XApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public XApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP のステータス。通信自体に失敗した場合は 0。 */
    public int statusCode() {
        return statusCode;
    }
}
