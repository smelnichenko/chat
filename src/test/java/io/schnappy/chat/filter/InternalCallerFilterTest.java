package io.schnappy.chat.filter;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCallerFilterTest {

    private static final String ADMIN =
            "spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-admin";
    private static final String XFCC_HEADER = "X-Forwarded-Client-Cert";

    @Test
    void configuredMatchingLastElement_proceeds() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, xfccElement(ADMIN));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void configuredWrongUri_forbidden() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, xfccElement(
                "spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chat"));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void spoofedFirstElementRealLastNonAdmin_forbidden() throws Exception {
        // Attacker prepends a forged admin element; the real proxy-appended last
        // element is a non-admin SA. Last-element selection must win → 403.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER,
                xfccElement(ADMIN) + ","
                        + xfccElement("spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chess"));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingXfccHeader_forbidden() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unsetConfig_proceeds() throws Exception {
        // Disabled: no XFCC header at all, yet the request still passes through.
        var filter = new InternalCallerFilter("");
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void nonInternalPath_notFiltered() throws Exception {
        // shouldNotFilter short-circuits anything outside /internal/.
        var filter = new InternalCallerFilter(ADMIN);
        var request = new MockHttpServletRequest("GET", "/api/rooms");
        request.setServletPath("/api/rooms");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void nullConfig_treatedAsDisabled_proceeds() throws Exception {
        // null collapses to "" in the constructor → check disabled.
        var filter = new InternalCallerFilter(null);
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    /**
     * Each header value resolves to no trusted admin URI, exercising a distinct
     * parse branch: blank header, element without a URI pair, a pair missing '='
     * (skipped), and an all-empty comma list (no last element).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "   ",
            "By=spiffe://x;Hash=abc123;Subject=\"\"",
            "bareflag;Hash=abc123",
            " , , "
    })
    void xfccWithNoTrustedUri_forbidden(String xfcc) throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, xfcc);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unquotedUriMatches_proceeds() throws Exception {
        // URI value carries no surrounding quotes → unquote returns it verbatim and it matches.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, "By=spiffe://x;Hash=abc123;URI=" + ADMIN);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void quotedUriMatches_proceeds() throws Exception {
        // Quoted URI value is unwrapped before comparison.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, "By=spiffe://x;Hash=abc123;URI=\"" + ADMIN + "\"");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void internalPathViaRequestUriWhenServletPathEmpty_isFiltered() throws Exception {
        // Empty servletPath forces the requestURI fallback in shouldNotFilter; the
        // /internal/ prefix still triggers the caller check (here: missing XFCC → 403).
        var filter = new InternalCallerFilter(ADMIN);
        var request = new MockHttpServletRequest("GET", "/internal/membership");
        request.setServletPath("");
        request.setRequestURI("/internal/membership");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest internalRequest() {
        var request = new MockHttpServletRequest("GET", "/internal/membership");
        request.setServletPath("/internal/membership");
        return request;
    }

    private static String xfccElement(String spiffe) {
        // Mirrors Istio's XFCC shape; URI carries the peer SPIFFE id.
        return "By=spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chat;"
                + "Hash=abc123;Subject=\"\";URI=" + spiffe;
    }
}
