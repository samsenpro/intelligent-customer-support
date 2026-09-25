package com.supportmind.config;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.auth.JwtAuthenticationFilter;
import com.supportmind.auth.JwtService;
import com.supportmind.auth.User;
import com.supportmind.auth.UserRepository;
import com.supportmind.exception.ProblemResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security: API stateless con JWT y RBAC. Las reglas por rol están en cada endpoint
 * ({@code @PreAuthorize}) y las de propiedad y organización en los servicios ({@code ConversationAccess}).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository,
                                       ProblemResponseWriter problemWriter) throws Exception {
        return http
                // API stateless con token en cabecera: no hay cookies de sesión que proteger de CSRF
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Las conexiones SSE ya se autorizaron al abrirse: sus despachos asíncronos (y los de
                        // error) no llevan de nuevo el token
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Solo accesible desde la red interna de Docker (Prometheus); nginx no lo publica
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Las reglas por rol también se aplican por URL: un usuario sin permiso recibe 403 antes de
                        // que se valide el cuerpo (los @PreAuthorize de los endpoints siguen como segunda barrera)
                        .requestMatchers("/api/v1/organizations/me/users/**", "/api/v1/organizations/me/ai-settings",
                                "/api/v1/audit-logs/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge", "/api/v1/knowledge/*/reindex")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/knowledge/*").hasRole("ADMIN")
                        .requestMatchers("/api/v1/analytics/**", "/api/v1/analytics").hasAnyRole("ADMIN", "SUPERVISOR")
                        .requestMatchers("/api/v1/customers/**", "/api/v1/customers", "/api/v1/knowledge/**",
                                "/api/v1/knowledge", "/api/v1/agents/**", "/api/v1/agents",
                                "/api/v1/conversations/*/ai/**")
                        .hasAnyRole("ADMIN", "SUPERVISOR", "AGENT")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/tickets/*").hasAnyRole("ADMIN", "SUPERVISOR", "AGENT")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemWriter).accessDeniedHandler(problemWriter))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository userRepository) {
        return email -> userRepository.findByEmail(User.normalizeEmail(email))
                .map(AuthenticatedUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        // DaoAuthenticationProvider compara un hash aunque el usuario no exista, para que el tiempo de
        // respuesta no revele qué emails están registrados
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
