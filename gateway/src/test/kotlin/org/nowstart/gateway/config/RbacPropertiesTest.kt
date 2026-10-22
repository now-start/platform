package org.nowstart.gateway.config

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.io.ClassPathResource

fun defaultRbac(): RbacProperties {
    val yaml = YamlPropertiesFactoryBean()
    yaml.setResources(ClassPathResource("application.yaml"))
    val values =
        requireNotNull(yaml.getObject()).entries.associate { it.key.toString() to it.value }
    return Binder(MapConfigurationPropertySource(values))
        .bind("gateway.rbac", RbacProperties::class.java)
        .get()
}

class RbacPropertiesTest {
    @Test
    fun mapsOnlyConfiguredGroupsIgnoringCase() {
        val rbac = defaultRbac()
        assertThat(rbac.authorityForGroup("users")).isEqualTo("ROLE_USERS")
        assertThat(rbac.authorityForGroup("UsErS")).isEqualTo("ROLE_USERS")
        assertThat(rbac.authorityForGroup("ADMINISTRATORS")).isEqualTo("ROLE_ADMINISTRATORS")
        assertThat(rbac.authorityForGroup("unknown")).isNull()
        assertThat(rbac.authorityForGroup("ROLE_USERS")).isNull()
    }

    @Test
    fun rejectsAmbiguousOrMissingAccessModeAndInvalidPaths() {
        assertThatIllegalArgumentException().isThrownBy { RbacProperties.Rule(listOf("/a")) }
        assertThatIllegalArgumentException().isThrownBy {
            RbacProperties.Rule(listOf("/a"), "USERS", true)
        }
        assertThatIllegalArgumentException().isThrownBy {
            RbacProperties.Rule(listOf("/a"), permitAll = true, authenticated = true)
        }
        assertThatIllegalArgumentException().isThrownBy {
            RbacProperties.Rule(listOf("/a"), "USERS", authenticated = true)
        }
        assertThatIllegalArgumentException().isThrownBy {
            RbacProperties.Rule(emptyList(), "USERS")
        }
        assertThatIllegalArgumentException().isThrownBy {
            RbacProperties.Rule(listOf("a"), "USERS")
        }
        assertThatThrownBy { RbacProperties.Rule(listOf("/{broken"), "USERS") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsInvalidRoleNamesAndAmbiguousGroups() {
        assertThatIllegalArgumentException().isThrownBy { defaultRbac().copy(defaultRole = "") }
        assertThatIllegalArgumentException().isThrownBy {
            defaultRbac().copy(defaultRole = "ROLE_ADMINISTRATORS")
        }
        assertThatIllegalArgumentException().isThrownBy {
            defaultRbac().copy(groupRoles = mapOf("team" to "USERS", "TEAM" to "ADMINISTRATORS"))
        }
        assertThatIllegalArgumentException().isThrownBy {
            defaultRbac().copy(groupsAttribute = " ")
        }
    }
}
