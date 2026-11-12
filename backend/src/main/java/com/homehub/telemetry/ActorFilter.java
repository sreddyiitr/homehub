package com.homehub.telemetry;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "Who invoked" (R-OBS-02): puts the caller on the HTTP server span, once per
 * request, so no controller has to.
 *
 * <p>The OTel agent's servlet instrumentation starts the server span BEFORE
 * any filter runs, so {@link Span#current()} here is that span.
 *
 * <p>Identity is the stand-in header {@code X-HomeHub-User} (OQ-1). Read
 * endpoints don't require it yet; write endpoints will reject callers
 * without it (Phase 3). A malformed value is never copied into telemetry —
 * a header is attacker-controlled input and could carry PII.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActorFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-HomeHub-User";
    private static final Pattern USER_ID = Pattern.compile("^(AGT|BUY)-[A-Z0-9]{3,32}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = Span.current();
        String user = request.getHeader(USER_HEADER);
        if (user != null && USER_ID.matcher(user).matches()) {
            span.setAttribute(HomeHubTelemetry.ENDUSER_ID, user);
            span.setAttribute(HomeHubTelemetry.ACTOR_ROLE, user.startsWith("AGT") ? "agent" : "buyer");
        } else {
            span.setAttribute(HomeHubTelemetry.ACTOR_ROLE, "anonymous");
        }
        span.setAttribute(HomeHubTelemetry.CLIENT_ADDRESS, request.getRemoteAddr());
        String ua = request.getHeader("User-Agent");
        if (ua != null) {
            span.setAttribute(HomeHubTelemetry.USER_AGENT, ua);
        }
        chain.doFilter(request, response);
    }
}
