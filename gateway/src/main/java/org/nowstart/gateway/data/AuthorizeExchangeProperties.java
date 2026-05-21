package org.nowstart.gateway.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.CollectionUtils;

@Data
@ConfigurationProperties(prefix = "security")
public class AuthorizeExchangeProperties {

    private List<Rule> rules;
    private List<Role> defaultRoles;
    private List<BasicUser> basicUsers;
    private Map<Role, List<String>> rolePaths;
    private List<String> users;
    private List<String> publicPaths;

    public List<PathRule> getPathRules() {
        List<PathRule> resolvedRules = new ArrayList<>();

        appendRules(resolvedRules, rules, getEffectiveDefaultRoles());
        appendRolePaths(resolvedRules);
        appendPaths(resolvedRules, users, List.of(Role.USERS));
        appendPaths(resolvedRules, publicPaths, List.of());

        return resolvedRules;
    }

    public List<Role> getEffectiveDefaultRoles() {
        if (CollectionUtils.isEmpty(defaultRoles)) {
            return List.of(Role.ADMINISTRATORS);
        }
        return defaultRoles;
    }

    private void appendRules(List<PathRule> resolvedRules, List<Rule> rules, List<Role> fallbackRoles) {
        if (rules != null) {
            rules.forEach(rule -> {
                List<Role> roles = rule.resolveRoles(fallbackRoles);

                rule.resolvePaths().forEach(path ->
                        resolvedRules.add(new PathRule(path, roles))
                );
            });
        }
    }

    private void appendRolePaths(List<PathRule> resolvedRules) {
        if (rolePaths == null) {
            return;
        }
        rolePaths.forEach((role, paths) -> appendPaths(resolvedRules, paths, List.of(role)));
    }

    private void appendPaths(List<PathRule> resolvedRules, List<String> paths, List<Role> roles) {
        if (paths == null) {
            return;
        }
        paths.forEach(path -> resolvedRules.add(new PathRule(path, roles)));
    }

    @Data
    public static class Rule {
        private String path;
        private List<String> paths;
        private Access access;
        private List<Role> roles;

        private List<String> resolvePaths() {
            List<String> resolvedPaths = new ArrayList<>();
            if (path != null) {
                resolvedPaths.add(path);
            }
            if (!CollectionUtils.isEmpty(paths)) {
                resolvedPaths.addAll(paths);
            }
            return resolvedPaths;
        }

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

    public record PathRule(String path, List<Role> roles) {
    }
}
