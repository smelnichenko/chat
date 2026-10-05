package io.schnappy.chat.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decoder Boot builds takes its keys from the refreshing source - one fetch shared with the warm-up - and that
 * source refreshes the set in the background before it expires. A stub JWK endpoint serves one RSA key and counts its
 * fetches.
 */
class KeycloakKeysConfigTest {

    private static final String ISSUER = "https://auth.example.test/realms/schnappy";

    private RSAKey key;
    private HttpServer server;
    private final AtomicInteger fetches = new AtomicInteger();
    private final CountDownLatch secondFetch = new CountDownLatch(2);

    @BeforeEach
    void serveKeys() throws JOSEException, IOException {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            fetches.incrementAndGet();
            secondFetch.countDown();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void bootsDecoderUsesTheRefreshingSourceItsKeysAreFetchedOnceForBoth() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(KeycloakKeysConfig.class)
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + certsUrl(),
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER)
                .run(context -> {
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    @SuppressWarnings("unchecked")
                    JWKSource<SecurityContext> source = context.getBean("keycloakJwkSource", JWKSource.class);

                    assertThat(decoder.decode(token(ISSUER)).getSubject()).isEqualTo("someone");
                    assertThat(decoder.decode(token(ISSUER)).getSubject()).isEqualTo("someone");
                    // the warm-up's call: served from the cache the decoder filled - Spring's own source has its own
                    assertThat(source.get(allKeys(), null)).hasSize(1);
                    assertThat(fetches).hasValue(1);
                });
    }

    @Test
    void bootsIssuerValidationStays() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(KeycloakKeysConfig.class)
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + certsUrl(),
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER)
                .run(context -> {
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    String foreign = token("https://elsewhere.test/realms/schnappy");

                    assertThatThrownBy(() -> decoder.decode(foreign))
                            .hasMessageContaining("iss");
                });
    }

    @Test
    void withoutAJwkSetUriThereIsNoKeySourceAsBootBuildsNoDecoder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(KeycloakKeysConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("keycloakJwkSource");
                    assertThat(context).doesNotHaveBean(JwtDecoder.class);
                });
    }

    @Test
    void theSetIsRefreshedInTheBackgroundBeforeItExpires() throws KeySourceException, InterruptedException {
        // 3 s to live, refreshed 1 s ahead (Nimbus wants ahead + refresh timeout below the time to live)
        JWKSource<SecurityContext> source = KeycloakKeysConfig.refreshingKeySource(
                certsUrl(), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(source.get(allKeys(), null)).hasSize(1);
        assertThat(fetches).hasValue(1);

        // no further get: only the scheduled refresh can make the second fetch, before the 3 s run out
        assertThat(secondFetch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fetches.get()).isGreaterThanOrEqualTo(2);
    }

    private URL certsUrl() {
        try {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/certs").toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWKSelector allKeys() {
        return new JWKSelector(new JWKMatcher.Builder().build());
    }

    private String token(String issuer) throws JOSEException {
        var claims = new JWTClaimsSet.Builder()
                .subject("someone")
                .issuer(issuer)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
