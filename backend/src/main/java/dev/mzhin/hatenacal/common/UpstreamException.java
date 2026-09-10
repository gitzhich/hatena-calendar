package dev.mzhin.hatenacal.common;

/**
 * 外部サービスに到達できない、またはエラーが返った。502 として返す（docs/api.md「エラー」）。
 *
 * <p><b>500 と分ける。</b> こちらの不具合ではなく相手側の事情なので、
 * 「想定外の例外」としてログに積むと本当の不具合が埋もれる（NFR-09）。
 * 利用者にとっても「時間をおけば直るかもしれない」と分かるほうがよい。
 *
 * <p><b>メッセージに相手の応答を含めない。</b> 内部構造を漏らさない（NFR-03）。
 * 詳しい理由はログにのみ残す。
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }
}
