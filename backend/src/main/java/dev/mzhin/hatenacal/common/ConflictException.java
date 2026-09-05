package dev.mzhin.hatenacal.common;

/** 一意制約違反。RFC 7807 の 409 として返す（docs/api.md「エラー」）。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
