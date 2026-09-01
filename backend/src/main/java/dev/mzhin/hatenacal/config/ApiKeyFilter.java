package dev.mzhin.hatenacal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Next.js から渡される内部 API キーを検証する（docs/api.md 第 2 章 / ADR-0010）。
 *
 * <p><b>キーは公開用と管理用の 2 種類。</b> 1 種類だと、公開ページの
 * レンダリングで使うキーが漏れただけで管理操作まで通ってしまう。
 * 公開データの取得は全ページで使われ露出機会が多いため、被害を限定する。
 *
 * <p><b>比較は固定時間で行う。</b> String#equals は先頭から順に比較して
 * 不一致で打ち切るため、応答時間の差からキーを 1 文字ずつ推測されうる。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String PUBLIC_HEADER = "X-Api-Key";
    public static final String ADMIN_HEADER = "X-Admin-Api-Key";
    public static final String ROLE_PUBLIC = "ROLE_PUBLIC_API";
    public static final String ROLE_ADMIN = "ROLE_ADMIN_API";

    private final byte[] publicKey;
    private final byte[] adminKey;

    public ApiKeyFilter(String publicKey, String adminKey) {
        this.publicKey = publicKey.getBytes(StandardCharsets.UTF_8);
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        if (matches(request.getHeader(PUBLIC_HEADER), publicKey)) {
            granted.add(new SimpleGrantedAuthority(ROLE_PUBLIC));
        }
        if (matches(request.getHeader(ADMIN_HEADER), adminKey)) {
            // 管理キーは公開 API も通せる。逆は通さない
            granted.add(new SimpleGrantedAuthority(ROLE_ADMIN));
            granted.add(new SimpleGrantedAuthority(ROLE_PUBLIC));
        }
        if (!granted.isEmpty()) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("internal", null, granted));
        }
        // 一致しなければ認証を設定しないだけ。理由を区別できる応答を返さない
        // （docs/api.md 第 2.2 節）。拒否は認可層が 403 で行う
        chain.doFilter(request, response);
    }

    private static boolean matches(String provided, byte[] expected) {
        if (provided == null || expected.length == 0) {
            return false; // キー未設定のときに空文字で通らないようにする
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
    }
}
