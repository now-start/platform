package org.nowstart.gateway.config;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CustomAuthoritiesFilterTest {

    private final CustomAuthoritiesFilter filter = new CustomAuthoritiesFilter();

    // ─── 공통 헬퍼 ───────────────────────────────────────────────────────────

    private Set<String> whenFilterExecuted(SecurityContext context) {
        var exchange = givenExchange();
        var chain = mock(WebFilterChain.class);
        given(chain.filter(exchange)).willReturn(Mono.empty());

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
                .block();

        return Objects.requireNonNull(context.getAuthentication()).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private ServerWebExchange givenExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    private OAuth2AuthenticationToken givenOAuth2Token(Map<String, Object> attributes, Collection<? extends GrantedAuthority> authorities) {
        var oauth2User = new DefaultOAuth2User(authorities, attributes, attributes.containsKey("sub") ? "sub" : attributes.keySet().iterator().next());
        return new OAuth2AuthenticationToken(oauth2User, authorities, "nowstart");
    }

    private Jwt givenJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .issuer("https://test-issuer.example.com")
                .subject("test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(claims))
                .build();
    }

    private SecurityContext givenSecurityContext(Authentication auth) {
        var context = new SecurityContextImpl();
        context.setAuthentication(auth);
        return context;
    }

    // ─── OAuth2 토큰 테스트 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("OAuth2 토큰에 groups 속성이 있을 때")
    class WhenOAuth2TokenHasGroups {

        @Test
        @DisplayName("groups가 단일 값이면 해당 권한만 매핑된다")
        void thenSingleGroupShouldBeMapped() {
            // given
            Map<String, Object> attributes = Map.of(
                    "sub", "user123",
                    "groups", List.of("administrators")
            );
            var context = givenSecurityContext(givenOAuth2Token(attributes, List.of()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).contains("ROLE_ADMINISTRATORS", "ROLE_USERS");
        }

        @Test
        @DisplayName("groups가 여러 개이면 모두 권한으로 매핑된다")
        void thenMultipleGroupsShouldAllBeMapped() {
            // given
            Map<String, Object> attributes = Map.of(
                    "sub", "user123",
                    "groups", List.of("administrators", "personal")
            );
            var context = givenSecurityContext(givenOAuth2Token(attributes, List.of()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).contains("ROLE_ADMINISTRATORS", "ROLE_USERS");
        }

        @Test
        @DisplayName("소문자 groups 값이면 대문자로 변환하여 매핑된다")
        void thenLowercaseGroupsShouldBeConvertedToUppercase() {
            // given
            Map<String, Object> attributes = Map.of(
                    "sub", "user123",
                    "groups", List.of("users")
            );
            var context = givenSecurityContext(givenOAuth2Token(attributes, List.of()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactly("ROLE_USERS");
        }

        @Test
        @DisplayName("알 수 없는 group 값이 포함되어 있으면 무시되고 유효한 값만 매핑된다")
        void thenUnknownGroupsShouldBeIgnored() {
            // given
            Map<String, Object> attributes = Map.of(
                    "sub", "user123",
                    "groups", List.of("unknown_group", "guest")
            );
            var context = givenSecurityContext(givenOAuth2Token(attributes, List.of()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactly("ROLE_USERS");
        }
    }

    @Nested
    @DisplayName("OAuth2 토큰에 groups 속성이 없을 때")
    class WhenOAuth2TokenHasNoGroups {

        @Test
        @DisplayName("groups가 null이면 기존 권한이 유지된다")
        void thenExistingAuthoritiesShouldBeKeptWhenGroupsIsNull() {
            // given
            var attributes = new HashMap<String, Object>();
            attributes.put("sub", "user123");
            attributes.put("groups", null);
            var existingAuthorities = List.of(new SimpleGrantedAuthority("EXISTING"));
            var context = givenSecurityContext(givenOAuth2Token(attributes, existingAuthorities));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactlyInAnyOrder("EXISTING", "ROLE_USERS");
        }

        @Test
        @DisplayName("groups 속성이 아예 없으면 기존 권한이 유지된다")
        void thenExistingAuthoritiesShouldBeKeptWhenGroupsAttributeMissing() {
            // given
            Map<String, Object> attributes = Map.of("sub", "user123");
            var existingAuthorities = List.of(new SimpleGrantedAuthority("EXISTING"));
            var context = givenSecurityContext(givenOAuth2Token(attributes, existingAuthorities));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactlyInAnyOrder("EXISTING", "ROLE_USERS");
        }
    }

    // ─── JWT 토큰 테스트 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("JWT 토큰에 groups claim이 있을 때")
    class WhenJwtTokenHasGroups {

        @Test
        @DisplayName("groups claim에서 올바르게 권한이 매핑된다")
        void thenGroupsShouldBeMappedToAuthorities() {
            // given
            var jwt = givenJwt(Map.of("groups", List.of("administrators")));
            var context = givenSecurityContext(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).contains("ROLE_ADMINISTRATORS", "ROLE_USERS");
        }

        @Test
        @DisplayName("소문자 groups claim이면 대문자로 변환하여 매핑된다")
        void thenLowercaseGroupsShouldBeConvertedToUppercase() {
            // given
            var jwt = givenJwt(Map.of("groups", List.of("users")));
            var context = givenSecurityContext(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactly("ROLE_USERS");
        }

        @Test
        @DisplayName("알 수 없는 group 값이 포함되어 있으면 무시되고 유효한 값만 매핑된다")
        void thenUnknownGroupsShouldBeIgnored() {
            // given
            var jwt = givenJwt(Map.of("groups", List.of("unknown_group", "administrators")));
            var context = givenSecurityContext(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactlyInAnyOrder("ROLE_ADMINISTRATORS", "ROLE_USERS");
        }
    }

    @Nested
    @DisplayName("JWT 토큰에 groups claim이 없을 때")
    class WhenJwtTokenHasNoGroups {

        @Test
        @DisplayName("groups claim이 없으면 기존 권한이 유지된다")
        void thenExistingAuthoritiesShouldBeKept() {
            // given
            var jwt = givenJwt(Map.of("sub", "user123"));
            var existingAuthorities = List.of(new SimpleGrantedAuthority("EXISTING"));
            var context = givenSecurityContext(new JwtAuthenticationToken(jwt, existingAuthorities, jwt.getSubject()));

            // when
            var authorities = whenFilterExecuted(context);

            // then
            then(authorities).containsExactlyInAnyOrder("EXISTING", "ROLE_USERS");
        }
    }

    // ─── SecurityContext 처리 테스트 ──────────────────────────────────────────

    @Nested
    @DisplayName("SecurityContext가 비어있을 때")
    class WhenSecurityContextIsEmpty {

        @Test
        @DisplayName("체인이 정상적으로 진행된다")
        void thenFilterChainShouldContinueNormally() {
            // given
            var exchange = givenExchange();
            var chain = mock(WebFilterChain.class);
            given(chain.filter(exchange)).willReturn(Mono.empty());

            // when & then
            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("SecurityContext가 존재할 때")
    class WhenSecurityContextExists {

        @Test
        @DisplayName("하위 WebFilterChain은 한 번만 호출되어야 한다")
        void thenFilterChainShouldOnlyBeInvokedOnce() {
            // given
            var jwt = givenJwt(Map.of("groups", List.of("administrators")));
            var context = givenSecurityContext(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));
            var exchange = givenExchange();
            var chain = mock(WebFilterChain.class);
            given(chain.filter(exchange)).willReturn(Mono.empty());

            // when & then
            StepVerifier.create(
                    filter.filter(exchange, chain)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
            ).verifyComplete();

            verify(chain, times(1)).filter(exchange);
        }

        @Test
        @DisplayName("Authentication이 null이어도 예외 없이 하위 체인을 진행해야 한다")
        void thenFilterChainShouldContinueWhenAuthenticationIsNull() {
            // given
            var context = givenSecurityContext(null);
            var exchange = givenExchange();
            var chain = mock(WebFilterChain.class);
            given(chain.filter(exchange)).willReturn(Mono.empty());

            // when & then
            StepVerifier.create(
                    filter.filter(exchange, chain)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
            ).verifyComplete();

            verify(chain, times(1)).filter(exchange);
        }
    }

    @Nested
    @DisplayName("지원하지 않는 Authentication 타입일 때")
    class WhenAuthenticationTypeIsUnsupported {

        @Test
        @DisplayName("예외 없이 체인이 정상적으로 진행된다")
        void thenFilterChainShouldContinueWithoutException() {
            // given
            var unsupportedAuth = mock(Authentication.class);
            var exchange = givenExchange();
            var chain = mock(WebFilterChain.class);

            given(unsupportedAuth.getAuthorities()).willReturn(List.of());
            given(chain.filter(exchange)).willReturn(Mono.empty());

            var context = givenSecurityContext(unsupportedAuth);

            // when & then
            StepVerifier.create(
                    filter.filter(exchange, chain)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
            ).verifyComplete();
        }
    }
}
