package dev.mzhin.hatenacal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 公開 API の総量制限を適用する（NFR-03 / docs/security.md T-04）。
 *
 * <p><b>認証を通ったリクエストだけ数える。</b> ApiKeyFilter の後ろに置き、
 * 権限が付いていないものは数えない。キーの無いリクエストは
 * どのみち 403 で DB に到達しないため、数えると<b>不正な通信が
 * 正規の枠を食い潰せてしまう</b>。
 *
 * <p>対象は公開 API のみ。管理 API と内部 API は Next.js のサーバ側からしか
 * 呼ばれず、量が読めているうえ、止まると訂正作業ができなくなる。
 */
public class PublicApiRateLimitFilter extends OncePerRequestFilter {

    private static final String PUBLIC_PATH_PREFIX = "/api/public/";

    private final PublicApiRateLimiter limiter;

    public PublicApiRateLimitFilter(PublicApiRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (applies(request) && !limiter.allow()) {
            // 本文を返さない。内部の状態を伝えない（NFR-03）
            response.setStatus(429);
            response.setHeader("Retry-After",
                    String.valueOf(PublicApiRateLimiter.WINDOW.toSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean applies(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith(PUBLIC_PATH_PREFIX)) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated();
    }
}
