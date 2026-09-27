package v2.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import v2.services.AuthTokenService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Проверяет Authorization: Bearer <токен> на ВСЕХ /api/**-запросах.
 *
 * Пропускаются без токена:
 *  - OPTIONS                  — CORS preflight (браузер не прикладывает к нему заголовки);
 *  - POST /api/v2/auth/login  — сам вход;
 *  - всё, если app.auth.key не задан (enabled() == false) — режим разработки;
 *  - ВНУТРЕННИЕ вызовы коннекторов/скриптов (isInternalRequest) — например,
 *    MAX-клиент PyMax сам обращается к http://localhost:8080/api/v2/... и
 *    браузерного токена у него нет и быть не должно;
 *  - запросы с заголовком X-Internal-Key (если задан app.auth.internalKey) —
 *    вариант для коннектора, который крутится на ДРУГОЙ машине.
 *
 * ВАЖНО про туннели и reverse-proxy: cloudflared / nginx тоже подключаются к
 * localhost, НО всегда добавляют заголовки (CF-Connecting-IP, X-Forwarded-For,
 * X-Real-IP…). Внутренним считается только запрос с loopback-адресом И без
 * единого прокси-заголовка — поэтому внешний трафик через туннель обойти
 * авторизацию не может: подделать ОТСУТСТВИЕ заголовков, которые добавляет
 * cloudflared, из интернета невозможно.
 *
 * Статика (messenger.html из src/main/resources/static) НЕ защищается — страница
 * грузится, но без токена не получит ни одного ответа от API и покажет экран входа.
 */
@Component
@Order(1)   // до остальных фильтров (в т.ч. до CORS-обработки, чтобы 401 не перехватывался)
public class AuthFilter extends OncePerRequestFilter {

    private final AuthTokenService tokens;
    /** Необязательный сервисный ключ для вызовов с других машин (пуст = выключен). */
    private final byte[] internalKey;

    public AuthFilter(AuthTokenService tokens,
                      @Value("${app.auth.internalKey:}") String internalKey) {
        this.tokens = tokens;
        this.internalKey = (internalKey == null ? "" : internalKey.trim()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String uri = req.getRequestURI();
        boolean isApi = uri.startsWith("/api/");
        boolean isLogin = uri.endsWith("/api/v2/auth/login");
        boolean isPreflight = "OPTIONS".equalsIgnoreCase(req.getMethod());

        if (!isApi || isLogin || isPreflight || !tokens.enabled() || isInternalRequest(req)) {
            chain.doFilter(req, resp);
            return;
        }

        String header = req.getHeader("Authorization");
        String token = (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7))
                ? header.substring(7).trim()
                : null;

        if (tokens.verify(token)) {
            chain.doFilter(req, resp);
            return;
        }

        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"success\":false,\"message\":\"Требуется авторизация: токен отсутствует или устарел\"}");
    }

    /**
     * Внутренний вызов = (а) сервисный ключ X-Internal-Key, либо
     * (б) прямое обращение с этой же машины МИНУЯ прокси/туннель:
     *     loopback-адрес и нет ни одного заголовка, который добавляют
     *     cloudflared/nginx (CF-Connecting-IP, X-Forwarded-*, X-Real-IP, Forwarded).
     */
    private boolean isInternalRequest(HttpServletRequest req) {
        // (а) сервисный ключ — для коннекторов на другой машине
        if (internalKey.length > 0) {
            String got = req.getHeader("X-Internal-Key");
            if (got != null && MessageDigest.isEqual(
                    got.trim().getBytes(StandardCharsets.UTF_8), internalKey)) {
                return true;
            }
        }
        // (б) прямой локальный вызов не через прокси
        String ip = req.getRemoteAddr();
        boolean loopback = ip != null
                && (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("127."));
        if (!loopback) {
            return false;
        }
        boolean proxied = req.getHeader("CF-Connecting-IP") != null
                || req.getHeader("X-Forwarded-For") != null
                || req.getHeader("X-Forwarded-Host") != null
                || req.getHeader("X-Forwarded-Proto") != null
                || req.getHeader("X-Real-IP") != null
                || req.getHeader("Forwarded") != null;
        return !proxied;
    }
}