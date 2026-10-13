package com.academy.paybridge.customer.web;

import com.academy.paybridge.shared.security.ApiKeyAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            List<ApiKeyAuthenticator> authenticators,
                                            ProblemDetailAuthenticationEntryPoint entryPoint,
                                            ProblemDetailAccessDeniedHandler accessDeniedHandler,
                                            Clock clock,
                                            @Value("${paybridge.rate-limit.enabled:true}") boolean rateLimitEnabled)
            throws Exception {

        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(authenticators, entryPoint);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // Documentation and health
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Open doors: registration, and Paystack's webhook (it proves itself by signature)
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/partner/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/paystack").permitAll()
                        // Partner endpoints: partners only
                        .requestMatchers("/api/v1/partner/**").hasRole("PARTNER")
                        // Everything else: customers only
                        .anyRequest().hasRole("CUSTOMER"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        if (rateLimitEnabled) {
            http.addFilterAfter(new RateLimitFilter(new RateLimiter(clock::millis)),
                    ApiKeyAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    UserDetailsService noPasswordLogin() {
        return username -> {
            throw new UsernameNotFoundException("Password login is not supported");
        };
    }
}