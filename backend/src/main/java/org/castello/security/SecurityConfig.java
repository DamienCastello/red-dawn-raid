package org.castello.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenAuthFilter tokenFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(reg -> reg
                        // Préflight CORS (important si front sur un autre domaine/port)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // DANGER WIPE (sans auth)
                        .requestMatchers(HttpMethod.POST, "/api/admin/wipe").permitAll()

                        // Auth public
                        .requestMatchers("/api/auth/**").permitAll()

                        // Dev tools
                        .requestMatchers("/api/dev/**").permitAll()

                        // Lecture publique de l'état de partie (optionnel)
                        .requestMatchers(HttpMethod.GET, "/api/games/**").permitAll()

                        // Autoriser /uploads/*
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/uploads/**").permitAll()

                        // Tout le reste des /api/games/** nécessite un user connecté (Bearer authToken)
                        .requestMatchers("/api/games/**").hasRole("USER")

                        .requestMatchers("/ws").permitAll()
                        .requestMatchers("/ws/**").permitAll()

                        // Par défaut on bloque
                        .anyRequest().denyAll()
                );

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var source = new UrlBasedCorsConfigurationSource();

        // --- API (strict) ---
        var api = new CorsConfiguration();
        api.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "https://red-dawn-raid-preprod.castello.ovh",
                "https://red-dawn-raid.castello.ovh"
        ));
        api.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        api.setAllowedHeaders(List.of("*"));
        api.setAllowCredentials(false);

        source.registerCorsConfiguration("/api/**", api);
        source.registerCorsConfiguration("/uploads/**", api);

        // --- WS (permissif, évite les 403 handshake derrière proxy) ---
        var ws = new CorsConfiguration();
        ws.setAllowedOriginPatterns(List.of("*"));  // ✅ important
        ws.setAllowedMethods(List.of("GET", "OPTIONS"));
        ws.setAllowedHeaders(List.of("*"));
        ws.setAllowCredentials(false);

        source.registerCorsConfiguration("/ws", ws);
        source.registerCorsConfiguration("/ws/**", ws);

        return source;
    }
}
