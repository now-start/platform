package org.nowstart.gateway.config

import java.util.HashMap
import java.util.stream.Collectors
import org.assertj.core.api.BDDAssertions.then
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.same
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class CustomAuthoritiesFilterTest {

    private val filter: CustomAuthoritiesFilter =
        CustomAuthoritiesFilter(
            RoleHierarchyImpl.fromHierarchy("ROLE_ADMINISTRATORS > ROLE_USERS"),
            defaultRbac(),
        )

    @Test
    @DisplayName("ADMINISTRATORS Basic 인증에는 USERS 계층 권한도 추가된다")
    fun thenBasicAdministratorShouldIncludeUsersAuthority() {
        // 준비
        val authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "basic-admin",
                "password",
                listOf(SimpleGrantedAuthority("ROLE_ADMINISTRATORS")),
            )
        val context = givenSecurityContext(authentication)

        // 실행
        val authorities = whenFilterExecuted(context)

        // 검증
        then(authorities).containsExactlyInAnyOrder("ROLE_ADMINISTRATORS", "ROLE_USERS")
    }

    @Test
    @DisplayName("권한 매핑은 요청을 변경하지 않고 원본 exchange를 전달한다")
    fun thenAuthorityMappingShouldPassOriginalExchange() {
        // 준비
        val authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "basic-admin",
                "password",
                listOf(SimpleGrantedAuthority("ROLE_ADMINISTRATORS")),
            )
        val context = givenSecurityContext(authentication)
        val exchange = givenExchange()
        val chain = mock(WebFilterChain::class.java)
        given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

        // 실행
        filter
            .filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
            .block()

        // 검증
        verify(chain).filter(same(exchange))
    }

    // ─── 공통 헬퍼 ───────────────────────────────────────────────────────────

    private fun whenFilterExecuted(context: SecurityContext): Set<String?> {
        val exchange = givenExchange()
        val chain = mock(WebFilterChain::class.java)
        given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

        filter
            .filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)))
            .block()

        return requireNotNull(context.getAuthentication())
            .getAuthorities()
            .stream()
            .map({ it.authority })
            .collect(Collectors.toSet())
    }

    private fun givenExchange(): ServerWebExchange {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
    }

    @Suppress("UNCHECKED_CAST")
    private fun givenOAuth2Token(
        attributes: Map<String, Any?>,
        authorities: Collection<GrantedAuthority>,
    ): OAuth2AuthenticationToken {
        val oauth2User =
            DefaultOAuth2User(
                authorities,
                attributes as Map<String, Any>,
                if (attributes.containsKey("sub")) "sub" else attributes.keys.first(),
            )
        return OAuth2AuthenticationToken(oauth2User, authorities, "nowstart")
    }

    private fun givenSecurityContext(auth: Authentication?): SecurityContext {
        val context = SecurityContextImpl()
        context.setAuthentication(auth)
        return context
    }

    // ─── OAuth2 토큰 테스트 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("OAuth2 토큰에 groups 속성이 있을 때")
    inner class WhenOAuth2TokenHasGroups {

        @Test
        @DisplayName("groups가 단일 값이면 해당 권한만 매핑된다")
        fun thenSingleGroupShouldBeMapped() {
            // 준비
            val attributes: Map<String, Any?> =
                mapOf("sub" to "user123", "groups" to listOf("administrators"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, listOf()))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).contains("ROLE_ADMINISTRATORS", "ROLE_USERS")
        }

        @Test
        @DisplayName("groups가 여러 개이면 모두 권한으로 매핑된다")
        fun thenMultipleGroupsShouldAllBeMapped() {
            // 준비
            val attributes: Map<String, Any?> =
                mapOf("sub" to "user123", "groups" to listOf("administrators", "personal"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, listOf()))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).contains("ROLE_ADMINISTRATORS", "ROLE_USERS")
        }

        @Test
        @DisplayName("소문자 groups 값이면 대문자로 변환하여 매핑된다")
        fun thenLowercaseGroupsShouldBeConvertedToUppercase() {
            // 준비
            val attributes: Map<String, Any?> =
                mapOf("sub" to "user123", "groups" to listOf("users"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, listOf()))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).containsExactly("ROLE_USERS")
        }

        @Test
        @DisplayName("알 수 없는 group 값만 포함되어 있으면 권한을 부여하지 않는다")
        fun thenUnknownGroupsShouldBeIgnored() {
            // 준비
            val attributes: Map<String, Any?> =
                mapOf("sub" to "user123", "groups" to listOf("unknown_group", "guest"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, listOf()))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).isEmpty()
        }
    }

    @Nested
    @DisplayName("OAuth2 토큰에 groups 속성이 없을 때")
    inner class WhenOAuth2TokenHasNoGroups {

        @Test
        @DisplayName("groups가 null이면 기존 권한만 유지된다")
        fun thenExistingAuthoritiesShouldBeKeptWhenGroupsIsNull() {
            // 준비
            val attributes = HashMap<String, Any?>()
            attributes.put("sub", "user123")
            attributes.put("groups", null)
            val existingAuthorities = listOf(SimpleGrantedAuthority("EXISTING"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, existingAuthorities))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).containsExactly("EXISTING")
        }

        @Test
        @DisplayName("groups 속성이 아예 없으면 기존 권한만 유지된다")
        fun thenExistingAuthoritiesShouldBeKeptWhenGroupsAttributeMissing() {
            // 준비
            val attributes: Map<String, Any?> = mapOf("sub" to "user123")
            val existingAuthorities = listOf(SimpleGrantedAuthority("EXISTING"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, existingAuthorities))

            // 실행
            val authorities = whenFilterExecuted(context)

            // 검증
            then(authorities).containsExactly("EXISTING")
        }
    }

    // ─── SecurityContext 처리 테스트 ──────────────────────────────────────────

    @Nested
    @DisplayName("SecurityContext가 비어있을 때")
    inner class WhenSecurityContextIsEmpty {

        @Test
        @DisplayName("체인이 정상적으로 진행된다")
        fun thenFilterChainShouldContinueNormally() {
            // 준비
            val exchange = givenExchange()
            val chain = mock(WebFilterChain::class.java)
            given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

            // 실행 및 검증
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()
        }
    }

    @Nested
    @DisplayName("SecurityContext가 존재할 때")
    inner class WhenSecurityContextExists {

        @Test
        @DisplayName("하위 WebFilterChain은 한 번만 호출되어야 한다")
        fun thenFilterChainShouldOnlyBeInvokedOnce() {
            // 준비
            val attributes: Map<String, Any?> =
                mapOf("sub" to "user123", "groups" to listOf("administrators"))
            val context = givenSecurityContext(givenOAuth2Token(attributes, listOf()))
            val exchange = givenExchange()
            val chain = mock(WebFilterChain::class.java)
            given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

            // 실행 및 검증
            StepVerifier.create(
                    filter
                        .filter(exchange, chain)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)),
                        ),
                )
                .verifyComplete()

            verify(chain, times(1)).filter(any(ServerWebExchange::class.java))
        }

        @Test
        @DisplayName("익명 인증은 변경하지 않고 원본 exchange로 체인을 진행한다")
        fun thenAnonymousAuthenticationShouldContinueWithOriginalExchange() {
            // 준비
            val authentication =
                AnonymousAuthenticationToken(
                    "key",
                    "anonymousUser",
                    listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
                )
            val context = givenSecurityContext(authentication)
            val exchange = givenExchange()
            val chain = mock(WebFilterChain::class.java)
            given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

            // 실행 및 검증
            StepVerifier.create(
                    filter
                        .filter(exchange, chain)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)),
                        ),
                )
                .verifyComplete()

            verify(chain).filter(same(exchange))
            then(context.getAuthentication()).isSameAs(authentication)
        }

        @Test
        @DisplayName("Authentication이 null이어도 예외 없이 하위 체인을 진행해야 한다")
        fun thenFilterChainShouldContinueWhenAuthenticationIsNull() {
            // 준비
            val context = givenSecurityContext(null)
            val exchange = givenExchange()
            val chain = mock(WebFilterChain::class.java)
            given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

            // 실행 및 검증
            StepVerifier.create(
                    filter
                        .filter(exchange, chain)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)),
                        ),
                )
                .verifyComplete()

            verify(chain, times(1)).filter(any(ServerWebExchange::class.java))
        }
    }

    @Nested
    @DisplayName("지원하지 않는 Authentication 타입일 때")
    inner class WhenAuthenticationTypeIsUnsupported {

        @Test
        @DisplayName("예외 없이 체인이 정상적으로 진행된다")
        fun thenFilterChainShouldContinueWithoutException() {
            // 준비
            val unsupportedAuth = mock(Authentication::class.java)
            val exchange = givenExchange()
            val chain = mock(WebFilterChain::class.java)

            given(unsupportedAuth.getAuthorities()).willReturn(listOf())
            given(chain.filter(any(ServerWebExchange::class.java))).willReturn(Mono.empty())

            val context = givenSecurityContext(unsupportedAuth)

            // 실행 및 검증
            StepVerifier.create(
                    filter
                        .filter(exchange, chain)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)),
                        ),
                )
                .verifyComplete()
        }
    }
}
