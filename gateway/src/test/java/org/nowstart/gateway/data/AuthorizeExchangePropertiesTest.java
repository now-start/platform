package org.nowstart.gateway.data;

import static org.assertj.core.api.BDDAssertions.then;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AuthorizeExchangePropertiesTest {

    private AuthorizeExchangeProperties givenProperties(
            List<String> users,
            List<String> publicPaths
    ) {
        AuthorizeExchangeProperties props = new AuthorizeExchangeProperties();
        props.setUsers(users);
        props.setPublicPaths(publicPaths);
        return props;
    }

    @Nested
    @DisplayName("users paths")
    class WhenUsersPathIsSet {

        @Test
        @DisplayName("users paths map to users role")
        void thenUsersRoleShouldBeGranted() {
            // given
            var props = givenProperties(List.of("/users/**"), null);

            // when
            var rules = props.getRules();

            // then
            then(rules).hasSize(1);
            then(rules.get(0).path()).isEqualTo("/users/**");
            then(rules.get(0).roles())
                    .containsExactly(Role.USERS);
        }
    }

    @Nested
    @DisplayName("public paths")
    class WhenPublicPathsAreSet {

        @Test
        @DisplayName("public paths map to empty roles")
        void thenRolesShouldBeEmpty() {
            // given
            var props = givenProperties(null, List.of("/actuator/**", "/public/**"));

            // when
            var rules = props.getRules();

            // then
            then(rules).hasSize(2);
            rules.forEach(rule -> then(rule.roles()).isEmpty());
        }
    }

    @Nested
    @DisplayName("combined paths")
    class WhenAllPathTypesAreCombined {

        @Test
        @DisplayName("each path gets the expected roles")
        void thenEachPathShouldHaveCorrectRoles() {
            // given
            var props = givenProperties(
                    List.of("/users/**"),
                    List.of("/public/**")
            );

            // when
            var rules = props.getRules();
            var ruleMap = rules.stream()
                    .collect(Collectors.toMap(
                            AuthorizeExchangeProperties.PathRule::path,
                            AuthorizeExchangeProperties.PathRule::roles
                    ));

            // then
            then(rules).hasSize(2);
            then(ruleMap.get("/users/**"))
                    .containsExactly(Role.USERS);
            then(ruleMap.get("/public/**"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("null properties")
    class WhenSomePropertiesAreNull {

        @Test
        @DisplayName("null properties are excluded")
        void thenNullPropertiesShouldBeExcluded() {
            // given
            var props = givenProperties(
                    List.of("/users/**"),
                    List.of("/public/**")
            );

            // when
            var rules = props.getRules();

            // then
            then(rules).hasSize(2);
            then(rules.stream().map(AuthorizeExchangeProperties.PathRule::path))
                    .containsExactlyInAnyOrder("/users/**", "/public/**");
        }
    }

    @Nested
    @DisplayName("empty list")
    class WhenPropertiesAreEmptyList {

        @Test
        @DisplayName("empty lists create no rules")
        void thenNoRulesShouldBeCreated() {
            // given
            var props = givenProperties(
                    List.of(),
                    List.of()
            );

            // when
            var rules = props.getRules();

            // then
            then(rules).isEmpty();
        }
    }

    @Nested
    @DisplayName("specific config")
    class WhenSpecificSecurityConfigIsProvided {

        @Test
        @DisplayName("configured paths are mapped correctly")
        void thenAllConfiguredPathsShouldBeMappedCorrectly() {
            // given
            var users = List.of(
                    "/admin/applications",
                    "/nyang-nyang-bot/authorization/**"
            );
            var publicPaths = List.of(
                    "/actuator/**",
                    "/nyang-nyang-bot/**"
            );
            var props = givenProperties(users, publicPaths);

            // when
            var rules = props.getRules();
            var ruleMap = rules.stream()
                    .collect(Collectors.toMap(
                            AuthorizeExchangeProperties.PathRule::path,
                            AuthorizeExchangeProperties.PathRule::roles,
                            (existing, replacement) -> existing
                    ));

            // then
            then(rules).hasSize(users.size() + publicPaths.size());

            users.forEach(path -> {
                then(ruleMap.get(path))
                        .as("Path %s should have users role", path)
                        .containsExactly(Role.USERS);
            });

            publicPaths.forEach(path -> {
                then(ruleMap.get(path))
                        .as("Path %s should be public (no roles)", path)
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("public paths always map to empty roles")
        void thenPublicPathsShouldHaveEmptyRolesForPermitAll() {
            // given
            var publicPaths = List.of("/health", "/info");
            var props = givenProperties(null, publicPaths);

            // when
            var rules = props.getRules();

            // then
            then(rules).hasSize(2);
            for (var rule : rules) {
                then(rule.roles())
                        .as("Public path %s must have empty roles for permitAll()", rule.path())
                        .isEmpty();
            }
        }
    }
}
