package com.miniredis.storage;

public record SetOptions(
        long exSeconds,
        long pxMillis,
        boolean isNx,
        boolean isXx,
        boolean getOld
) {
    public static SetOptions defaults() {
        return new SetOptions(0, 0, false, false, false);
    }

    public static SetOptions ex(long seconds) {
        return new SetOptions(seconds, 0, false, false, false);
    }

    public static SetOptions px(long millis) {
        return new SetOptions(0, millis, false, false, false);
    }

    public static SetOptions nx() {
        return new SetOptions(0, 0, true, false, false);
    }

    public static SetOptions xx() {
        return new SetOptions(0, 0, false, true, false);
    }
}
