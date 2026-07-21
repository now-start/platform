package org.nowstart.gateway.data;

import static org.assertj.core.api.BDDAssertions.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RoleTest {

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

}
