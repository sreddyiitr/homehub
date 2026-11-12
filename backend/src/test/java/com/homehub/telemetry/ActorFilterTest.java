package com.homehub.telemetry;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Uses the OTel SDK's in-memory exporter: we play the role of the agent's
 * servlet instrumentation (start a SERVER span, make it current), run the
 * filter inside it, then assert on the exported span.
 */
class ActorFilterTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private SpanData runFilter(MockHttpServletRequest request) throws Exception {
        Span server = otel.getOpenTelemetry().getTracer("test").spanBuilder("GET /x").startSpan();
        MockFilterChain chain = new MockFilterChain();
        try (Scope ignored = server.makeCurrent()) {
            new ActorFilter().doFilter(request, new MockHttpServletResponse(), chain);
        } finally {
            server.end();
        }
        assertThat(chain.getRequest()).as("chain continued").isSameAs(request);
        return otel.getSpans().get(0);
    }

    private static MockHttpServletRequest request(String user) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/listings/search");
        r.setRemoteAddr("10.1.2.3");
        r.addHeader("User-Agent", "homehub-test/1.0");
        if (user != null) {
            r.addHeader(ActorFilter.USER_HEADER, user);
        }
        return r;
    }

    @Test
    @DisplayName("R-OBS-02 buyer identity lands on the server span")
    void buyer() throws Exception {
        assertThat(runFilter(request("BUY-004")))
                .hasAttribute(HomeHubTelemetry.ENDUSER_ID, "BUY-004")
                .hasAttribute(HomeHubTelemetry.ACTOR_ROLE, "buyer")
                .hasAttribute(HomeHubTelemetry.CLIENT_ADDRESS, "10.1.2.3")
                .hasAttribute(HomeHubTelemetry.USER_AGENT, "homehub-test/1.0");
    }

    @Test
    void agent() throws Exception {
        assertThat(runFilter(request("AGT-002")))
                .hasAttribute(HomeHubTelemetry.ENDUSER_ID, "AGT-002")
                .hasAttribute(HomeHubTelemetry.ACTOR_ROLE, "agent");
    }

    @Test
    void anonymousWithoutHeader() throws Exception {
        SpanData span = runFilter(request(null));
        assertThat(span).hasAttribute(HomeHubTelemetry.ACTOR_ROLE, "anonymous");
        assertThat(span.getAttributes().get(HomeHubTelemetry.ENDUSER_ID)).isNull();
    }

    @Test
    @DisplayName("R-PII-01 a malformed identity header is never copied into telemetry")
    void malformedHeaderNotRecorded() throws Exception {
        SpanData span = runFilter(request("jane.doe@example.com"));

        assertThat(span).hasAttribute(HomeHubTelemetry.ACTOR_ROLE, "anonymous");
        assertThat(span.getAttributes().asMap().values()).noneMatch(v -> v.toString().contains("@"));
    }

    @Test
    void missingUserAgentIsOmitted() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/");
        assertThat(runFilter(r).getAttributes().get(HomeHubTelemetry.USER_AGENT)).isNull();
    }
}
