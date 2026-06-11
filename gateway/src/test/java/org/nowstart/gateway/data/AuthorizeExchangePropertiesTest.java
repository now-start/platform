package org.nowstart.gateway.data;

import static org.assertj.core.api.BDDAssertions.then;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizeExchangePropertiesTest {

    @Test
    @DisplayName("rules 설정은 path와 roles를 보안 규칙으로 변환한다")
    void rulesShouldBeMappedToPathRules() {
        // given
        var refreshRule = new AuthorizeExchangeProperties.Rule();
        refreshRule.setPath("/admin/settings");
        refreshRule.setRoles(List.of(Role.ADMINISTRATORS));

        var props = new AuthorizeExchangeProperties();
        props.setRules(List.of(refreshRule));

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(1);
        then(rules.getFirst().path()).isEqualTo("/admin/settings");
        then(rules.getFirst().roles()).containsExactly(Role.ADMINISTRATORS);
    }

    @Test
    @DisplayName("public access rule은 roles가 없는 permitAll 규칙으로 변환한다")
    void publicRuleShouldHaveNoRoles() {
        // given
        var publicRule = new AuthorizeExchangeProperties.Rule();
        publicRule.setPaths(List.of("/actuator/**", "/nyang-nyang-bot/**"));
        publicRule.setAccess(AuthorizeExchangeProperties.Access.PUBLIC);

        var props = new AuthorizeExchangeProperties();
        props.setRules(List.of(publicRule));

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(2);
        then(rules).extracting(AuthorizeExchangeProperties.PathRule::path)
                .containsExactly("/actuator/**", "/nyang-nyang-bot/**");
        then(rules).allSatisfy(rule -> then(rule.roles()).isEmpty());
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
    @DisplayName("rolePaths 설정은 Role별 권한 규칙으로 변환한다")
    void rolePathsShouldBeMappedToRoleRules() {
        // given
        var props = new AuthorizeExchangeProperties();
        Map<Role, List<String>> rolePaths = new LinkedHashMap<>();
        rolePaths.put(Role.USERS, List.of("/admin", "/admin/applications"));
        rolePaths.put(Role.ADMINISTRATORS, List.of("/admin/settings"));
        props.setRolePaths(rolePaths);

        // when
        var rules = props.getPathRules();

        // then
        then(rules).hasSize(3);
        then(rules).extracting(AuthorizeExchangeProperties.PathRule::path)
                .containsExactly("/admin", "/admin/applications", "/admin/settings");
        then(rules).extracting(AuthorizeExchangeProperties.PathRule::roles)
                .containsExactly(
                        List.of(Role.USERS),
                        List.of(Role.USERS),
                        List.of(Role.ADMINISTRATORS)
                );
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
