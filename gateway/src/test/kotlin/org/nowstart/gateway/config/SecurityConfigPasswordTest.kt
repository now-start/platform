package org.nowstart.gateway.config

import org.assertj.core.api.BDDAssertions.thenCode
import org.assertj.core.api.BDDAssertions.thenThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.mock
import org.nowstart.gateway.audit.AuditClientAddressResolver

class SecurityConfigPasswordTest {

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "{bcrypt}\$2a\$10\$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
                "{bcrypt}\$2b\$10\$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
                "{bcrypt}\$2y\$10\$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
            ],
    )
    @DisplayName("명시적인 BCrypt 비밀번호 형식은 허용한다")
    fun validBcryptPasswordShouldBeAccepted(password: String) {
        thenCode(
                {
                    SecurityConfig(
                        mock(GatewayAuthenticationEntryPoint::class.java),
                        password,
                        defaultRbac(),
                        AuditClientAddressResolver(""),
                    )
                },
            )
            .doesNotThrowAnyException()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings =
            [
                "",
                "plain-password",
                "{noop}plain-password",
                "\$2a\$10\$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
                "{bcrypt}\$2a\$10\$short",
                "{cipher}not-decrypted",
            ],
    )
    @DisplayName("복호화 결과가 명시적인 BCrypt 형식이 아니면 거부한다")
    fun invalidBcryptPasswordShouldBeRejected(password: String?) {
        thenThrownBy(
                {
                    SecurityConfig(
                        mock(GatewayAuthenticationEntryPoint::class.java),
                        password,
                        defaultRbac(),
                        AuditClientAddressResolver(""),
                    )
                },
            )
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("spring.security.user.password must be an explicit BCrypt hash")
    }
}
