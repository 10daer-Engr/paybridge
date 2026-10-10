package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CustomerApi customerApi,
                                            ProblemDetailAuthenticationEntryPoint entryPoint,
                                            Clock clock,
                                            @Value("${paybridge.rate-limit.enabled:true}") boolean rateLimitEnabled)
            throws Exception {

        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(customerApi, entryPoint);

        http
                // Answers browser preflight (OPTIONS) requests before authentication runs; without this they get a 401.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/paystack").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
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