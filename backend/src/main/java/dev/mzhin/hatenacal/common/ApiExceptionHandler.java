package dev.mzhin.hatenacal.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import java.util.stream.Collectors;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 7807（Problem Details）で返す（docs/api.md 第 3.3 節）。
 *
 * <p><b>スタックトレース・SQL・内部のクラス名をレスポンスに含めない</b>（NFR-03）。
 * 詳細はログにのみ残す。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Conflict");
        detail.setDetail(e.getMessage());
        return detail;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Not Found");
        detail.setDetail(e.getMessage());
        return detail;
    }

    /** Bean Validation 違反。項目名と理由だけを返し、内部構造は出さない。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        return badRequest(e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", ")));
    }

    /**
     * クエリパラメータの欠落（docs/api.md 第 3.3 節「クエリパラメータの形式不正」）。
     *
     * <p><b>個別に受けないと 500 になる。</b> {@code @ExceptionHandler(Exception.class)} は
     * この例外にも一致し、Spring の既定の変換（{@code DefaultHandlerExceptionResolver}）より
     * 先に解決されるため、契約どおりの 400 が返らなくなる。下の 2 つも同じ理由。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException e) {
        return badRequest(e.getParameterName() + " は必須です");
    }

    /**
     * クエリパラメータ・パス変数の型変換失敗（{@code from=abc} など）。
     *
     * <p><b>受け取った値も例外のメッセージも載せない</b>（NFR-03）。
     * 既定のメッセージには変換先の型名とメソッドのシグネチャが入る。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName() + " の形式が不正です");
    }

    /** リクエストボディが読めない（JSON として壊れている・空である）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        return badRequest("リクエストボディの形式が不正です");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("想定外の例外", e);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Internal Server Error");
        // 内部構造を漏らさないため、原因はレスポンスに入れない
        detail.setDetail("サーバ内部エラーが発生しました");
        return detail;
    }

    /** 400 は形が同じなので 1 か所で組む。title は第 3.3 節の例に合わせる。 */
    private static ProblemDetail badRequest(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Validation Failed");
        detail.setDetail(message);
        return detail;
    }
}
