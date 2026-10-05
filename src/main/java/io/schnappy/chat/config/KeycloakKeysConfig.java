package io.schnappy.chat.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keycloak's signing keys, fetched once before the app takes traffic and then refreshed in the background ahead of
 * their expiry.
 *
 * <p>Spring Security's own JWK source caches the key set for five minutes with refresh-ahead off: the first request
 * after a start fetched it from Keycloak (on the Pis, over TLS), and so did the first request after every expiry - one
 * request per pod every five minutes waited on Keycloak. This source refreshes the set on a schedule before it expires,
 * and {@link StartupWarmUp} fetches it before the app reports ready. Boot's decoder stays as configured (its
 * algorithms, issuer validation and type check): only its key selector is replaced, with the same algorithms.
 */
@Configuration(proxyBeanMethods = false)
public class KeycloakKeysConfig {

    /** Nimbus's defaults, as Spring Security's source had them: five minutes, at most 15 s for one refresh. */
    static final Duration CACHE_TTL = Duration.ofMillis(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE);
    static final Duration REFRESH_TIMEOUT = Duration.ofMillis(JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT);
    /** The background refresh starts this long before the cached set expires. */
    static final Duration REFRESH_AHEAD = Duration.ofMillis(JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final int SIZE_LIMIT_BYTES = 50 * 1024;

    @Bean
    JWKSource<SecurityContext> keycloakJwkSource(OAuth2ResourceServerProperties properties)
            throws MalformedURLException {
        String jwkSetUri = properties.getJwt().getJwkSetUri();
        Assert.state(jwkSetUri != null, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri is not set");
        return refreshingKeySource(URI.create(jwkSetUri).toURL(), CACHE_TTL, REFRESH_TIMEOUT, REFRESH_AHEAD);
    }

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer keycloakKeySelector(JWKSource<SecurityContext> keycloakJwkSource,
                                                             OAuth2ResourceServerProperties properties) {
        Set<JWSAlgorithm> algorithms = properties.getJwt().getJwsAlgorithms().stream()
                .map(JWSAlgorithm::parse)
                .collect(Collectors.toUnmodifiableSet());
        return builder -> builder.jwtProcessorCustomizer(processor ->
                processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keycloakJwkSource)));
    }

    /**
     * A cached JWK source whose set is refreshed on a schedule {@code refreshAhead} before it expires, so no request
     * waits for a refresh while Keycloak answers.
     */
    static JWKSource<SecurityContext> refreshingKeySource(URL jwkSetUrl, Duration cacheTtl, Duration refreshTimeout,
                                                          Duration refreshAhead) {
        var retriever = new DefaultResourceRetriever(
                (int) HTTP_TIMEOUT.toMillis(), (int) HTTP_TIMEOUT.toMillis(), SIZE_LIMIT_BYTES);
        return JWKSourceBuilder.<SecurityContext>create(jwkSetUrl, retriever)
                .cache(cacheTtl.toMillis(), refreshTimeout.toMillis())
                .refreshAheadCache(refreshAhead.toMillis(), true)
                .rateLimited(false)
                .build();
    }
}
