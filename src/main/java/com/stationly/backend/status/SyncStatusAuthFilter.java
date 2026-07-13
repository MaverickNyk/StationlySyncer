package com.stationly.backend.status;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional bearer-key gate for the status API.
 *
 * <ul>
 *   <li>{@code syncer.status.key} unset → API is open (fine for a localhost-only bind).</li>
 *   <li>{@code /health} is ALWAYS open — pure liveness, no sensitive data — so probes/
 *       load-balancers can hit it without a credential.</li>
 *   <li>{@code /sync-status*} requires the key via {@code Authorization: Bearer <key>}
 *       or {@code X-Stationly-Key: <key>} (same conventions stationly-admin already uses).</li>
 * </ul>
 */
@Component
public class SyncStatusAuthFilter implements WebFilter {

    @Value("${syncer.status.key:}")
    private String statusKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (statusKey == null || statusKey.isEmpty()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (path.equals("/health") || !path.startsWith("/sync-status")) {
            return chain.filter(exchange);
        }

        String provided = bearer(exchange);
        if (provided == null || !constantTimeEquals(provided, statusKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static String bearer(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return exchange.getRequest().getHeaders().getFirst("X-Stationly-Key");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
