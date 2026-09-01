package dev.mzhin.hatenacal.common;

/** 一意制約違反。RFC 7807 の 409 として返す（docs/api.md 第 3.3 節）。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
