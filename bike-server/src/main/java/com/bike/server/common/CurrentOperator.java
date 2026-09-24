package com.bike.server.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentOperator {
    private CurrentOperator() {
    }

    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof com.bike.server.auth.UserAuthRecord user) return user.id();
        return null;
    }

    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof com.bike.server.auth.UserAuthRecord user) {
            if (role.equals("WEB_AGENT") && user.userType().equals("AGENT")) return true;
            if (role.equals("PDA_OPERATOR") && user.userType().equals("PDA_OPERATOR")) return true;
        }
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    public static boolean hasPermission(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PERM_" + permission));
    }

    public static void requireAnyPermission(String... permissions) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        requireAuthenticated(authentication);
        if (hasRole("SUPER_ADMIN")) return;
        for (String permission : permissions) if (hasPermission(permission)) return;
        throw new BusinessException("PERMISSION_DENIED", "当前账号无权执行该操作");
    }

    public static void requireWebAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new BusinessException("UNAUTHORIZED", "请先登录");
        if (hasRole("PDA_OPERATOR")) throw new BusinessException("PERMISSION_DENIED", "PDA 操作员只能进入工位作业页面");
    }

    public static void requireRole(String role) {
        requireAuthenticated(SecurityContextHolder.getContext().getAuthentication());
        if (!hasRole(role)) throw new BusinessException("PERMISSION_DENIED", "当前账号无权执行该操作");
    }

    public static void requireInternalWebAccess() {
        requireWebAccess();
        if (hasRole("WEB_AGENT")) throw new BusinessException("FORBIDDEN", "代理商账号不能访问内部业务资料");
    }

    public static void requireAnyRole(String... roles) {
        requireAuthenticated(SecurityContextHolder.getContext().getAuthentication());
        for (String role : roles) if (hasRole(role)) return;
        throw new BusinessException("PERMISSION_DENIED", "当前账号无权执行该操作");
    }

    public static String clientIp() {
        try {
            jakarta.servlet.http.HttpServletRequest request =
                    ((org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            String value = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
            return value == null ? null : value.substring(0, Math.min(64, value.length()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static void requireWorkstation(String workstationType) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        requireAuthenticated(authentication);
        if (hasRole("WEB_AGENT")) throw new BusinessException("FORBIDDEN", "代理商不能执行内部工位操作");
        if (hasRole("SUPER_ADMIN")) return;
        String authority = "WORKSTATION_" + workstationType.toUpperCase(java.util.Locale.ROOT);
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(authority))) {
            throw new BusinessException("WORKSTATION_FORBIDDEN", "当前账号无权操作该工位");
        }
    }

    public static void requirePermission(String permissionCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        requireAuthenticated(authentication);
        if (hasRole("SUPER_ADMIN")) return;
        String authority = "PERM_" + permissionCode;
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(authority))) {
            throw new BusinessException("PERMISSION_DENIED", "当前账号无权执行该操作");
        }
    }

    public static Long agentId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof com.bike.server.auth.UserAuthRecord user) return user.agentId();
        return null;
    }

    /** Null means unrestricted internal scope, or the separately enforced agent scope. */
    public static Long customerScopeUserId() {
        requireAuthenticated(SecurityContextHolder.getContext().getAuthentication());
        if (hasRole("WEB_AGENT")) return null;
        if (hasRole("SUPER_ADMIN") || hasPermission("CUSTOMER_SCOPE_ALL")) return null;
        Long user = id();
        return user == null ? -1L : user;
    }

    private static void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("UNAUTHORIZED", "请先登录");
        }
    }
}
