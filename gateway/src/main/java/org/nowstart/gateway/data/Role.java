package org.nowstart.gateway.data;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public enum Role {
    USERS,
    ADMINISTRATORS(USERS);

    private static final String AUTHORITY_PREFIX = "ROLE_";
    private static final Map<String, Role> BY_NAME = Stream.of(values())
            .collect(toUnmodifiableMap(Role::name, Function.identity()));

    private final List<Role> includes;

    Role(Role... includes) {
        this.includes = Arrays.asList(includes);
    }

    private static Stream<Role> apply(Role r) {
        return r.getAllIncluded().stream();
    }

    public static Role from(String name) {
        if (name == null) {
            return null;
        }
        return BY_NAME.get(name.toUpperCase(Locale.ROOT));
    }

    public static String[] acceptedNames(List<Role> requiredRoles) {
        return Stream.of(values())
                .filter(candidate -> requiredRoles.stream().anyMatch(candidate::includes))
                .map(Role::name)
                .toArray(String[]::new);
    }

    public List<Role> getAllIncluded() {
        return Stream.concat(
                Stream.of(this),
                includes.stream().flatMap(Role::apply)
        ).distinct().toList();
    }

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    public boolean includes(Role role) {
        return getAllIncluded().contains(role);
    }
}
