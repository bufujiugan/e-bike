package com.bike.server.module;

import java.util.List;

public record ModulePageResponse(
        String subtitle,
        List<Stat> stats,
        List<String> columns,
        List<List<String>> rows,
        String action,
        long page,
        long pageSize,
        long total) {
    public ModulePageResponse(String subtitle, List<Stat> stats, List<String> columns, List<List<String>> rows, String action) {
        this(subtitle, stats, columns, rows, action, 1, rows.size(), rows.size());
    }

    public record Stat(String label, String value, String tone) { }
}
