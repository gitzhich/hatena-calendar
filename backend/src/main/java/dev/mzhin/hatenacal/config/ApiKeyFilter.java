package dev.mzhin.hatenacal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Next.js から渡される内部 API キーを検証する（docs/api.md 第 2 章 / ADR-0010）。
 *
 * <p>キーは公開用と管理用の 2 種類に分かれる。ここで扱うのは公開用。
 *
 * <p><b>比較は固定時間で行う。</b> String#equals は先頭から順に比較して
 * 不一致で打ち切るため、応答時間の差からキーを 1 文字ずつ推測されうる。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String ROLE_PUBLIC = "ROLE_PUBLIC_API";

    private final byte[] expected;

    public ApiKeyFilter(String expected) {
        this.expected = expected.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && matches(provided)) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("public-api", null,
                            List.of(new SimpleGrantedAuthority(ROLE_PUBLIC))));
        }
        // 一致しなければ認証を設定しないだけ。理由を区別できる応答を返さない
        // （docs/api.md 第 2.2 節）。拒否は認可層が 403 で行う。
        chain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
    }
}
