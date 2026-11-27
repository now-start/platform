package org.nowstart.gateway.config

import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.AuthorizationGrantType

class OAuth2RedirectUriTest {
    @Test
    fun usesPublicHttpsCallbackWhenProxyConnectsOverHttp() {
        val environment = MockEnvironment()
        YamlPropertySourceLoader()
            .load(
                "gateway-config",
                FileSystemResource("../config/src/main/resources/config/gateway/gateway.yaml"),
            )
            .forEach { environment.propertySources.addLast(it) }
        val registration =
            Binder.get(environment)
                .bind(
                    "spring.security.oauth2.client.registration.nowstart",
                    OAuth2ClientProperties.Registration::class.java,
                )
                .get()
        val client =
            ClientRegistration.withRegistrationId("nowstart")
                .clientId("test-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .redirectUri(registration.redirectUri)
                .build()
        val resolver =
            DefaultServerOAuth2AuthorizationRequestResolver(
                InMemoryReactiveClientRegistrationRepository(client),
            )
        val request =
            MockServerHttpRequest.get("http://gateway:8000/oauth2/authorization/nowstart")
                .header(HttpHeaders.HOST, "spring.nowstart.org")
                .build()
        val authorization =
            requireNotNull(resolver.resolve(MockServerWebExchange.from(request)).block())

        assertThat(URI.create(authorization.authorizationRequestUri))
            .hasParameter("redirect_uri", "https://spring.nowstart.org/login/oauth2/code/nowstart")
    }
}
