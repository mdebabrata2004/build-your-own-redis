package com.miniredis.security;

public enum Permission {
    READ,
    WRITE,
    ADMIN,
    EXPIRE,
    FLUSH;

    public static boolean matches(String scope, Permission required) {
        if (scope == null) return false;
        String s = scope.trim().toLowerCase();
        if (s.equals("admin")) return true;
        return s.equals(required.name().toLowerCase());
    }
}
