package org.nowstart.gateway.data;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;

@Data
@ConfigurationProperties(prefix = "security")
public class AuthorizeExchangeProperties {

    private List<Rule> rules;
    private List<Role> defaultRoles;
    private List<BasicUser> basicUsers;
    private List<String> users;
    private List<String> publicPaths;

    public List<PathRule> getPathRules() {
        List<PathRule> resolvedRules = new ArrayList<>();

        if (rules != null) {
            rules.forEach(rule ->
                    resolvedRules.add(new PathRule(
                            rule.getPath(),
                            methodsOrEmpty(rule.getMethods()),
                            rule.resolveRoles(getEffectiveDefaultRoles())
                    ))
            );
        }

        if (users != null) {
            users.forEach(path ->
                    resolvedRules.add(new PathRule(path, List.of(), List.of(Role.USERS)))
            );
        }

        if (publicPaths != null) {
            publicPaths.forEach(path -> resolvedRules.add(new PathRule(path, List.of(), List.of())));
        }

        return resolvedRules;
    }

    public List<Role> getEffectiveDefaultRoles() {
        if (CollectionUtils.isEmpty(defaultRoles)) {
            return List.of(Role.ADMINISTRATORS);
        }
        return defaultRoles;
    }

    private List<HttpMethod> methodsOrEmpty(List<HttpMethod> methods) {
        if (methods == null) {
            return List.of();
        }
        return methods;
    }

    @Data
    public static class Rule {
        private String path;
        private List<HttpMethod> methods;
        private Access access;
        private List<Role> roles;

        private List<Role> resolveRoles(List<Role> defaultRoles) {
            if (access == Access.PUBLIC) {
                return List.of();
            }
            if (CollectionUtils.isEmpty(roles)) {
                return defaultRoles;
            }
            return roles;
        }
    }

    public enum Access {
        PUBLIC
    }

    @Data
    public static class BasicUser {
        private String username;
        private String password;
        private List<Role> roles;

        public List<Role> getEffectiveRoles() {
            if (CollectionUtils.isEmpty(roles)) {
                return List.of(Role.ADMINISTRATORS);
            }
            return roles;
        }
    }

    public record PathRule(String path, List<HttpMethod> methods, List<Role> roles) {
    }
}
