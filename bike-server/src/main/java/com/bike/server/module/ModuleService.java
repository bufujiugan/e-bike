package com.bike.server.module;

public interface ModuleService {
    ModulePageResponse list(String module);

    default ModulePageResponse list(String module, String keyword, long page, long pageSize) {
        return list(module);
    }
}
