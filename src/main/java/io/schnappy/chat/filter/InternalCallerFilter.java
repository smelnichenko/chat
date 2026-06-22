package io.schnappy.chat.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * App-layer authorization guard for {@code /internal/**}, defense-in-depth
 * behind the Istio mesh DENY policy.
 *
 * <p>The internal endpoints are reached only by the admin service over Istio
 * mTLS. The mesh-level AuthorizationPolicy already restricts the source
 * service account, but that is a single point of failure: one policy
 * regression would expose these endpoints. This filter independently pins the
 * trusted caller's SPIFFE identity so an Istio misconfiguration alone cannot
 * open the door.
 *
 * <p>Identity is taken from the {@code X-Forwarded-Client-Cert} (XFCC) header
 * that Istio's inbound sidecar stamps from the verified peer certificate. Istio
 * uses {@code forward_client_cert_details: APPEND_FORWARD}, so the LAST element
 * is the one this pod's own inbound proxy appended and is therefore the only
 * trustworthy peer identity — a malicious caller can prepend spoofed elements
 * but cannot forge the trailing one.
 *
 * <p>When {@code internal.allowed-caller} is unset the check is disabled (a
 * single WARN at construction), preserving existing behavior for any
 * environment that has not yet configured the SPIFFE principal.
 */
@Component
public class InternalCallerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalCallerFilter.class);
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String XFCC_HEADER = "X-Forwarded-Client-Cert";
    private static final String URI_KEY = "uri";

    private final String allowedCaller;
    private final boolean enabled;

    public InternalCallerFilter(@Value("${internal.allowed-caller:}") String allowedCaller) {
        this.allowedCaller = allowedCaller == null ? "" : allowedCaller.strip();
        this.enabled = !this.allowedCaller.isEmpty();
        if (!this.enabled) {
            log.warn("internal.allowed-caller unset; /internal mTLS caller check DISABLED");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path == null || !path.startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String xfcc = request.getHeader(XFCC_HEADER);
        if (xfcc == null || xfcc.isBlank()) {
            log.warn("Rejected /internal call: missing {} header", XFCC_HEADER);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String caller = extractCallerUri(xfcc);
        if (caller != null && caller.equals(allowedCaller)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rejected /internal call from unauthorized principal: {}", caller);
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * Extracts the peer SPIFFE URI from the last XFCC element (the one this
     * pod's inbound proxy appended under APPEND_FORWARD). Returns {@code null}
     * if no URI key is present.
     */
    private static String extractCallerUri(String xfcc) {
        String lastElement = lastElement(xfcc);
        if (lastElement == null) {
            return null;
        }
        // Each element is a ';'-separated list of key=value pairs.
        for (String pair : lastElement.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq).strip();
            if (URI_KEY.equalsIgnoreCase(key)) {
                return unquote(pair.substring(eq + 1).strip());
            }
        }
        return null;
    }

    private static String lastElement(String xfcc) {
        // XFCC is a comma-separated list of elements; APPEND_FORWARD means the
        // trustworthy peer identity is the final one.
        String[] elements = xfcc.split(",");
        for (int i = elements.length - 1; i >= 0; i--) {
            String element = elements[i].strip();
            if (!element.isEmpty()) {
                return element;
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
