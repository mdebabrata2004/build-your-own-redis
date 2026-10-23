package com.miniredis.storage;

import java.util.List;

public record ScanResult(
        String nextCursor,
        List<String> keys
) {}
