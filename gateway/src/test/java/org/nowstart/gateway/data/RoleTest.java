package org.nowstart.gateway.data;

import static org.assertj.core.api.BDDAssertions.then;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Nested
    @DisplayName("USERS role")
    class WhenRoleIsUsers {

        @Test
        @DisplayName("contains only itself")
        void thenShouldContainItselfAndUsers() {
            // given
            var role = Role.USERS;

            // when
            var included = role.getAllIncluded();

            // then
            then(included).containsExactly(Role.USERS);
        }
    }

    @Nested
    @DisplayName("ADMINISTRATORS role")
    class WhenRoleIsAdministrators {

        @Test
        @DisplayName("contains only itself")
        void thenShouldContainOnlyItself() {
            // given
            var role = Role.ADMINISTRATORS;

            // when
            var included = role.getAllIncluded();

            // then
            then(included)
                    .containsExactly(Role.ADMINISTRATORS, Role.USERS);
        }
    }

    @Nested
    @DisplayName("role authority")
    class WhenResolvingAuthority {

        @Test
        @DisplayName("adds ROLE_ prefix")
        void thenShouldAddRolePrefix() {
            then(Role.ADMINISTRATORS.authority()).isEqualTo("ROLE_ADMINISTRATORS");
        }
    }

    @Nested
    @DisplayName("role parsing")
    class WhenParsingRole {

        @Test
        @DisplayName("matches case-insensitively")
        void thenShouldParseIgnoringCase() {
            then(Role.from("administrators")).isEqualTo(Role.ADMINISTRATORS);
        }

        @Test
        @DisplayName("returns null for unknown values")
        void thenShouldReturnNullForUnknownValues() {
            then(Role.from("guest")).isNull();
        }
    }

    @Nested
    @DisplayName("accepted role names")
    class WhenResolvingAcceptedRoleNames {

        @Test
        @DisplayName("users routes also accept administrators")
        void thenUsersRoutesShouldAlsoAcceptAdministrators() {
            then(Role.acceptedNames(List.of(Role.USERS)))
                    .containsExactly(Role.USERS.name(), Role.ADMINISTRATORS.name());
        }
    }

    @Nested
    @DisplayName("duplicate check")
    class WhenCheckingDuplicates {

        @Test
        @DisplayName("returns distinct roles")
        void thenShouldNotContainDuplicates() {
            // given & when & then
            for (Role role : Role.values()) {
                // when
                var included = role.getAllIncluded();
                var distinctCount = included.stream().distinct().count();

                // then
                then(distinctCount)
                        .as("Role %s has duplicates", role)
                        .isEqualTo(included.size());
            }
        }
    }
}
