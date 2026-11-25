package com.bike.server.module;

public interface ModuleService {
    ModulePageResponse list(String module);

    default ModulePageResponse list(String module, String keyword, long page, long pageSize) {
        return list(module);
    }

    /** salesTypes 只对订单模块生效：逗号分隔的 DOMESTIC/EXPORT/EUROPE/PENDING。 */
    default ModulePageResponse list(String module, String keyword, String salesTypes, long page, long pageSize) {
        return list(module, keyword, page, pageSize);
    }

    default ModulePageResponse list(String module, String keyword, String salesTypes, String transportArrangement,
                                    long page, long pageSize) {
        return list(module, keyword, salesTypes, page, pageSize);
    }
}
