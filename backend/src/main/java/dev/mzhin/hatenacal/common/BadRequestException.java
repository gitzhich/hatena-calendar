package dev.mzhin.hatenacal.common;

/** クライアントの指定が不正。RFC 7807 の 400 として返す（docs/api.md「エラー」）。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
