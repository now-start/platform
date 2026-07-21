package org.nowstart.gateway.data;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public enum Role {
    USERS,
    ADMINISTRATORS;

    private static final String AUTHORITY_PREFIX = "ROLE_";
    private static final Map<String, Role> BY_NAME = Stream.of(values())
            .collect(toUnmodifiableMap(Role::name, Function.identity()));

    public static Role from(String name) {
        if (name == null) {
            return null;
        }
        return BY_NAME.get(name.toUpperCase(Locale.ROOT));
    }

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
