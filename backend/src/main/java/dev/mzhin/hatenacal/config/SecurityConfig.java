package dev.mzhin.hatenacal.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * <b>デフォルト拒否</b>にし、公開エンドポイントだけを明示的に許可する（NFR-03）。
 *
 * <p>許可漏れではなく拒否漏れを防ぐ設計。新しいエンドポイントを足したとき、
 * ここに書き忘れれば通らなくなる（安全側に倒れる）。
 */
@Configuration
public class SecurityConfig {

    private final String publicApiKey;
    private final String adminApiKey;

    public SecurityConfig(@Value("${INTERNAL_API_KEY:}") String publicApiKey,
            @Value("${INTERNAL_ADMIN_API_KEY:}") String adminApiKey) {
        this.publicApiKey = publicApiKey;
        this.adminApiKey = adminApiKey;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // API キーによるステートレス認証。Cookie を使わないため CSRF の対象にならない。
                // ブラウザからは呼ばれず、Next.js からのサーバ間通信のみ
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // 未認証と認可失敗をどちらも 403 にする。401 を返すと
                // 「キーが無い」と「キーが違う」を区別できてしまう
                // （docs/api.md 第 2.2 節）
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.setStatus(HttpServletResponse.SC_FORBIDDEN))
                        .accessDeniedHandler((req, res, e) ->
                                res.setStatus(HttpServletResponse.SC_FORBIDDEN)))
                .addFilterBefore(new ApiKeyFilter(publicApiKey, adminApiKey),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // 公開 API は GET のみ。他のメソッドを許可しない
                        .requestMatchers(HttpMethod.GET, "/api/public/**")
                        .hasAuthority(ApiKeyFilter.ROLE_PUBLIC)
                        // 管理 API と内部 API は管理キーだけが通る。
                        // 公開キーでは通らない（ADR-0010）
                        .requestMatchers("/api/admin/**", "/internal/**")
                        .hasAuthority(ApiKeyFilter.ROLE_ADMIN)
                        .requestMatchers("/actuator/health").permitAll()
                        // 明示的に許可したもの以外はすべて拒否する
                        .anyRequest().denyAll());
        return http.build();
    }
}
