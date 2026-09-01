package dev.mzhin.hatenacal.common;

/** 指定した ID のリソースが存在しない。404 として返す。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
