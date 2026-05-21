package org.nowstart.gateway.data;

import static org.assertj.core.api.BDDAssertions.then;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AuthorizeExchangePropertiesTest {

    @Test
    @DisplayName("rules 설정은 path, method, roles를 보안 규칙으로 변환한다")
    void rulesShouldBeMappedToPathRules() {
        // given
        var refreshRule = new AuthorizeExchangeProperties.Rule();
        refreshRule.setPath("/internal/config-refresh");
        refreshRule.setMethods(List.of(HttpMethod.POST));
        refreshRule.setRoles(List.of(Role.ADMINISTRATORS));

        var props = new AuthorizeExchangeProperties();
        props.setRules(List.of(refreshRule));

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(1);
        then(rules.getFirst().path()).isEqualTo("/internal/config-refresh");
        then(rules.getFirst().methods()).containsExactly(HttpMethod.POST);
        then(rules.getFirst().roles()).containsExactly(Role.ADMINISTRATORS);
    }

    @Test
    @DisplayName("public access rule은 roles가 없는 permitAll 규칙으로 변환한다")
    void publicRuleShouldHaveNoRoles() {
        // given
        var publicRule = new AuthorizeExchangeProperties.Rule();
        publicRule.setPath("/actuator/**");
        publicRule.setAccess(AuthorizeExchangeProperties.Access.PUBLIC);

        var props = new AuthorizeExchangeProperties();
        props.setRules(List.of(publicRule));

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(1);
        then(rules.getFirst().path()).isEqualTo("/actuator/**");
        then(rules.getFirst().methods()).isEmpty();
        then(rules.getFirst().roles()).isEmpty();
    }

    @Test
    @DisplayName("roles가 없는 비공개 rule은 defaultRoles를 사용한다")
    void privateRuleWithoutRolesShouldUseDefaultRoles() {
        // given
        var privateRule = new AuthorizeExchangeProperties.Rule();
        privateRule.setPath("/admin/**");

        var props = new AuthorizeExchangeProperties();
        props.setDefaultRoles(List.of(Role.USERS));
        props.setRules(List.of(privateRule));

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(1);
        then(rules.getFirst().roles()).containsExactly(Role.USERS);
    }

    @Test
    @DisplayName("defaultRoles가 없으면 ADMINISTRATORS를 기본 권한으로 사용한다")
    void defaultRolesShouldFallbackToAdministrators() {
        // given
        var props = new AuthorizeExchangeProperties();

        // when & then
        then(props.getEffectiveDefaultRoles()).containsExactly(Role.ADMINISTRATORS);
    }

    @Test
    @DisplayName("legacy users/publicPaths 설정도 기존 규칙으로 변환한다")
    void legacyPropertiesShouldStillBeMapped() {
        // given
        var props = new AuthorizeExchangeProperties();
        props.setUsers(List.of("/admin/applications"));
        props.setPublicPaths(List.of("/actuator/**"));

        // when
        var ruleMap = props.getPathRules().stream()
                .collect(Collectors.toMap(
                        AuthorizeExchangeProperties.PathRule::path,
                        AuthorizeExchangeProperties.PathRule::roles
                ));

        // then
        then(ruleMap.get("/admin/applications")).containsExactly(Role.USERS);
        then(ruleMap.get("/actuator/**")).isEmpty();
    }
}
