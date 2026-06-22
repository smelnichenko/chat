package io.schnappy.chat.config;

import io.schnappy.chat.filter.GatewayAuthFilter;
import io.schnappy.chat.filter.InternalCallerFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security config for downstream service behind the Istio ingress.
 * Istio validates the Keycloak JWT at the edge; GatewayAuthFilter reads
 * the propagated token and populates the SecurityContext for handlers.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayAuthFilter gatewayAuthFilter;
    private final InternalCallerFilter internalCallerFilter;

    public SecurityConfig(GatewayAuthFilter gatewayAuthFilter, InternalCallerFilter internalCallerFilter) {
        this.gatewayAuthFilter = gatewayAuthFilter;
        this.internalCallerFilter = internalCallerFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/ws/**").permitAll()
                // mTLS-fronted; Istio AuthZ DENY for non-admin source SAs.
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // App-layer authz for /internal/** — runs before the JWT filter so the
            // mTLS peer identity is pinned independently of the Istio mesh policy.
            .addFilterBefore(internalCallerFilter, GatewayAuthFilter.class);

        return http.build();
    }
}
