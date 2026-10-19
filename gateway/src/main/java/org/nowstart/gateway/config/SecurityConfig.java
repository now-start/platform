package org.nowstart.gateway.config;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.nowstart.gateway.data.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpBasicServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.WebFilterChainServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String DEFAULT_OAUTH2_SUCCESS_LOCATION = "/admin";
    private static final String INVALID_BASIC_PASSWORD_MESSAGE =
            "spring.security.user.password must be an explicit BCrypt hash";
    private static final Pattern BCRYPT_PASSWORD_PATTERN = Pattern.compile(
            "\\{bcrypt\\}\\$2[aby]\\$(?:0[4-9]|[12]\\d|3[01])\\$[./0-9A-Za-z]{53}"
    );
    private static final String[] OAUTH2_LOGIN_PATHS = {
            "/oauth2/authorization/**",
            "/login/oauth2/code/**"
    };
    private static final String[] PUBLIC_PATHS = {
            "/actuator/**",
            "/nyang-nyang-bot/**",
            "/barosteel",
            "/barosteel/**"
    };
    private static final String[] USER_PATHS = {
            "/admin",
            "/admin/",
            "/admin/applications",
            "/admin/wallboard",
            "/admin/journal",
            "/admin/external",
            "/admin/assets/**",
            "/admin/*.*",
            "/admin/swagger-ui/**",
            "/*/v3/api-docs",
            "/admin/v3/api-docs/swagger-config"
    };

    private final GatewayAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(
            GatewayAuthenticationEntryPoint authenticationEntryPoint,
            @Value("${spring.security.user.password}") String basicPassword
    ) {
        if (basicPassword == null || !BCRYPT_PASSWORD_PATTERN.matcher(basicPassword).matches()) {
            throw new IllegalStateException(INVALID_BASIC_PASSWORD_MESSAGE);
        }
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, RoleHierarchy roleHierarchy, AuditEventRepository audit) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .headers(headers -> headers
                        .frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                        .contentSecurityPolicy(csp -> csp.policyDirectives("upgrade-insecure-requests; frame-ancestors 'self'"))
                )
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler((exchange, denied) -> exchange.getPrincipal()
                                .map(Principal::getName).defaultIfEmpty("anonymousUser")
                                .flatMap(name -> recordAudit(audit, exchange, name, "AUTHORIZATION_FAILURE"))
                                .then(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN).handle(exchange, denied))))
                .authorizeExchange(this::authorizeExchange)
                .httpBasic(basic -> basic
                        .authenticationSuccessHandler((exchange, authentication) ->
                                recordAudit(audit, exchange.getExchange(), authentication.getName(), "AUTHENTICATION_SUCCESS")
                                        .then(new WebFilterChainServerAuthenticationSuccessHandler()
                                                .onAuthenticationSuccess(exchange, authentication)))
                        .authenticationFailureHandler((exchange, failure) ->
                                recordAudit(audit, exchange.getExchange(), "anonymousUser", "AUTHENTICATION_FAILURE")
                                        .then(new HttpBasicServerAuthenticationEntryPoint().commence(exchange.getExchange(), failure))))
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler((exchange, authentication) ->
                                recordAudit(audit, exchange.getExchange(), authentication.getName(), "AUTHENTICATION_SUCCESS")
                                        .then(oAuth2SuccessHandler().onAuthenticationSuccess(exchange, authentication)))
                        .authenticationFailureHandler((exchange, failure) ->
                                recordAudit(audit, exchange.getExchange(), "anonymousUser", "AUTHENTICATION_FAILURE")
                                        .then(new RedirectServerAuthenticationFailureHandler("/login?error")
                                                .onAuthenticationFailure(exchange, failure))))
                .addFilterAfter(new CustomAuthoritiesFilter(roleHierarchy), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMINISTRATORS.name())
                .implies(Role.USERS.name())
                .build();
    }

    @Bean
    public ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }

    private void authorizeExchange(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
        exchanges.pathMatchers(OAUTH2_LOGIN_PATHS).permitAll()
                .pathMatchers(USER_PATHS).hasRole(Role.USERS.name())
                .pathMatchers(PUBLIC_PATHS).permitAll()
                .anyExchange().hasRole(Role.ADMINISTRATORS.name());
    }

    static Mono<Void> recordAudit(AuditEventRepository repository, ServerWebExchange exchange, String principal, String type) {
        return exchange.getSession().flatMap(session -> {
            Map<String, Object> details = new LinkedHashMap<>();
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            if (remote != null) {
                details.put("remoteAddress", remote.getHostString());
            }
            // 감사 때문에 빈 세션을 시작하거나 쿠키를 만들지 않는다.
            if (session.isStarted()) {
                details.put("sessionId", session.getId());
            }
            AuditEvent event = new AuditEvent(principal, type, Map.of("details", details));
            return Mono.fromRunnable(() -> repository.add(event)).subscribeOn(Schedulers.boundedElastic()).then();
        }).onErrorResume(failure -> {
            // 예외 메시지에 연결 정보나 개인정보가 포함될 수 있어 타입만 기록한다.
            log.warn("Gateway audit write failed: type={}", failure.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    private RedirectServerAuthenticationSuccessHandler oAuth2SuccessHandler() {
        RedirectServerAuthenticationSuccessHandler successHandler =
                new RedirectServerAuthenticationSuccessHandler(DEFAULT_OAUTH2_SUCCESS_LOCATION);
        successHandler.setRequestCache(new WebSessionServerRequestCache());
        return successHandler;
    }
}
