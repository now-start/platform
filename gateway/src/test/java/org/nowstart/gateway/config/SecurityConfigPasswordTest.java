package org.nowstart.gateway.config;

import static org.assertj.core.api.BDDAssertions.thenCode;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.mockito.BDDMockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityConfigPasswordTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "{bcrypt}$2a$10$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
            "{bcrypt}$2b$10$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
            "{bcrypt}$2y$10$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO"
    })
    @DisplayName("명시적인 BCrypt 비밀번호 형식은 허용한다")
    void validBcryptPasswordShouldBeAccepted(String password) {
        thenCode(() -> new SecurityConfig(mock(GatewayAuthenticationEntryPoint.class), password))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "plain-password",
            "{noop}plain-password",
            "$2a$10$Y0HAJQ/lEa/63WX7RuNr.eBIVKUOgr9QH6C/IKL.Es4jvXnabr7XO",
            "{bcrypt}$2a$10$short",
            "{cipher}not-decrypted"
    })
    @DisplayName("복호화 결과가 명시적인 BCrypt 형식이 아니면 거부한다")
    void invalidBcryptPasswordShouldBeRejected(String password) {
        thenThrownBy(() -> new SecurityConfig(mock(GatewayAuthenticationEntryPoint.class), password))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("spring.security.user.password must be an explicit BCrypt hash");
    }
}
