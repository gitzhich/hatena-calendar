package dev.mzhin.hatenacal.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理者パスワードの検証（docs/api.md 第 6.1 節）。
 *
 * <p>Next.js のログイン処理からのみ呼ばれる。DB にユーザーテーブルを持たず、
 * 環境変数の BCrypt ハッシュと照合する（FR-20）。
 */
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthController.class);

    /** パスワードを含むため、この record をログに出さない。 */
    public record AuthRequest(@NotNull String password) {
    }

    public record AuthResponse(boolean authenticated) {
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String passwordHash;
    private final LoginAttemptLimiter limiter;

    public InternalAuthController(@Value("${ADMIN_PASSWORD_HASH:}") String passwordHash,
            LoginAttemptLimiter limiter) {
        this.passwordHash = passwordHash;
        this.limiter = limiter;
    }

    /**
     * <b>失敗時も 200 を返す。</b> ステータスコードで成否を区別すると
     * 総当たりの判定材料になる（docs/api.md 第 6.1 節 / T-02）。
     */
    @PostMapping
    public AuthResponse authenticate(@RequestBody AuthRequest request,
            HttpServletRequest http) {
        String key = http.getRemoteAddr();
        if (!limiter.allow(key)) {
            // 超過中は照合そのものを行わない。応答は成否と区別できない形にする
            log.warn("ログイン試行が上限を超えている");
            return new AuthResponse(false);
        }
        if (passwordHash.isBlank()) {
            log.error("ADMIN_PASSWORD_HASH が未設定のため認証できない");
            return new AuthResponse(false);
        }
        boolean ok = encoder.matches(request.password(), passwordHash);
        if (ok) {
            limiter.recordSuccess(key);
        } else {
            limiter.recordFailure(key);
        }
        // パスワードも照合結果の詳細もログに出さない（NFR-03）
        return new AuthResponse(ok);
    }
}
