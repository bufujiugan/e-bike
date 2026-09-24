package com.bike.server.module;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ModuleServiceImpl implements ModuleService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private static final Set<String> SUPPORTED_MODULES = Set.of(
            "assembly", "vehicles", "inventory", "stock", "orders", "agents", "trace",
            "parts", "customers", "suppliers", "models", "warehouses", "users", "roles",
            "workstations", "logs", "customs", "locations", "inventory-locks", "stock-in", "stock-out", "rework",
            "operation-logs", "config", "agent-dashboard", "agent-inventory", "agent-locks",
            "purchase-intents");

    private static final Map<String, ModuleDefinition> DEFINITIONS = definitions();
    private final ModuleMapper moduleMapper;

    public ModuleServiceImpl(ModuleMapper moduleMapper) {
        this.moduleMapper = moduleMapper;
    }

    @Override
    public ModulePageResponse list(String module) {
        return list(module, null, 1, 100);
    }

    @Override
    public ModulePageResponse list(String module, String keyword, long page, long pageSize) {
        if (!SUPPORTED_MODULES.contains(module)) {
            throw new BusinessException("MODULE_NOT_SUPPORTED", "不支持的业务模块");
        }
        if (CurrentOperator.hasRole("WEB_AGENT") && !Set.of("orders", "agent-dashboard", "agent-inventory", "agent-locks", "purchase-intents").contains(module)) {
            throw new BusinessException("FORBIDDEN", "代理商账号无权访问该模块");
        }
        if (CurrentOperator.hasRole("WEB_AGENT") && CurrentOperator.agentId() == null) {
            throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定代理商");
        }
        requireModulePermission(module);
        requireModuleAccess(module);
        customerScope.lockForBusiness();
        ModuleDefinition definition = DEFINITIONS.get(module);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        long normalizedPage = Math.min(10_000_000, Math.max(1, page));
        long normalizedSize = Math.min(100, Math.max(1, pageSize));
        Long agentId = CurrentOperator.hasRole("WEB_AGENT") ? CurrentOperator.agentId() : null;
        String sortColumn = List.of("更新时间", "操作时间", "时间", "组装时间", "开始时间", "创建时间", "入库日期", "查询时间")
                .stream().filter(definition.columns()::contains).findFirst().orElse(definition.columns().getFirst());
        boolean financialAudit = com.bike.server.common.FinancialAuditAccess.allowed();
        long total = moduleMapper.countRows(normalize(module), agentId, definition.columns(), normalizedKeyword, financialAudit);
        List<List<String>> rows = moduleMapper.selectRowsPage(normalize(module), agentId, definition.columns(), normalizedKeyword,
                        sortColumn, (normalizedPage - 1) * normalizedSize, normalizedSize, financialAudit).stream()
                .map(row -> definition.columns().stream().map(column -> display(row.get(column))).toList()).toList();
        return new ModulePageResponse(definition.subtitle(), definition.stats(total), definition.columns(), rows, definition.action(), normalizedPage, normalizedSize, total);
    }

    private static void requireModuleAccess(String module) {
        if (Set.of("users", "roles", "workstations", "config").contains(module)) {
            CurrentOperator.requireRole("SUPER_ADMIN");
            return;
        }
        if (Set.of("agent-dashboard", "agent-inventory", "agent-locks", "purchase-intents", "inventory-locks").contains(module)) {
            CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ", "AGENT_LOCK_WRITE");
            return;
        }
        if (Set.of("assembly", "stock-in", "stock-out", "rework").contains(module)) {
            CurrentOperator.requireWebAccess();
            return;
        }
        CurrentOperator.requireWebAccess();
    }

    private static void requireModulePermission(String module) {
        switch (module) {
            case "vehicles" -> CurrentOperator.requirePermission("VEHICLE_READ");
            case "inventory" -> CurrentOperator.requireAnyPermission("INVENTORY_READ", "AGENT_INVENTORY_READ");
            case "stock", "stock-in", "stock-out" -> CurrentOperator.requirePermission("STOCK_READ");
            case "orders" -> CurrentOperator.requirePermission("ORDER_READ");
            case "agents" -> CurrentOperator.requirePermission("MASTER_READ");
            case "trace" -> CurrentOperator.requirePermission("TRACE_READ");
            case "parts" -> CurrentOperator.requirePermission("PART_READ");
            case "customers" -> CurrentOperator.requireAnyPermission("MASTER_READ","CUSTOMER_PROFILE_WRITE");
            case "suppliers", "models", "warehouses" -> CurrentOperator.requirePermission("MASTER_READ");
            case "locations" -> CurrentOperator.requireAnyPermission("MASTER_READ", "INVENTORY_READ", "STOCK_IN_OPERATE");
            case "assembly" -> CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
            case "rework" -> CurrentOperator.requirePermission("REWORK_OPERATE");
            case "logs", "operation-logs", "customs" -> CurrentOperator.requirePermission("DATA_EXPORT");
            default -> {
                // 系统管理和代理商专用模块由 requireModuleAccess 继续执行更严格的角色/范围校验。
            }
        }
    }

    private static String normalize(String module) {
        return switch (module) {
            case "operation-logs" -> "logs";
            case "stock-in", "stock-out" -> module;
            case "inventory-locks", "agent-locks" -> "inventory-locks";
            case "agent-dashboard" -> "agent-dashboard";
            case "agent-inventory" -> "agent-inventory";
            case "purchase-intents" -> "purchase-intents";
            default -> module;
        };
    }

    private static String display(Object value) {
        if (value == null) return "-";
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toString().replace('T', ' ');
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        return String.valueOf(value);
    }

    private static Map<String, ModuleDefinition> definitions() {
        Map<String, ModuleDefinition> definitions = new LinkedHashMap<>();
        definitions.put("assembly", definition("生产任务实际扫码进度与最近扫码工位", List.of("车架 SN", "当前工位", "完成进度", "操作员", "开始时间", "状态", "任务号"), "新建组装任务"));
        definitions.put("vehicles", definition("按车架、部件、SKU、批次、状态和订单查询整车档案", List.of("整车编号", "车架 SN", "电机 SN", "电池 SN", "仪表 SN", "整车状态", "车型", "组装时间", "入库时间", "出库时间", "客户", "订单", "仓库", "库位", "SKU", "生产批次"), "导出整车档案"));
        definitions.put("inventory", definition("库位数量为车型展示分摊；车型SKU缺口独立显示，末三列为全车型数值不可跨库位累计。具体SKU申请上限请进入SKU库存锁定", List.of("车型", "仓库", "库位", "物理库存", "锁定库存", "分摊可新增上限", "库龄", "入库日期", "隔离库存", "临时锁定", "订单占用", "库存缺口", "车型SKU缺口（不累计）", "历史车型承诺（不累计）", "车型可新增上限（不累计）"), "锁定库存"));
        definitions.put("stock", definition("整车入库、出库及撤销记录", List.of("业务单号", "类型", "整车数量", "客户 / 仓库", "操作人", "操作时间", "状态"), "新建出入库单"));
        definitions.put("stock-in", definition("整车入库记录及撤销状态", List.of("业务单号", "类型", "整车数量", "客户 / 仓库", "操作人", "操作时间", "状态"), "办理入库"));
        definitions.put("stock-out", definition("整车出库记录及撤销状态", List.of("业务单号", "类型", "整车数量", "客户 / 仓库", "操作人", "操作时间", "状态"), "办理出库"));
        definitions.put("orders", definition("客户订单、出库进度与车辆明细", List.of("订单号", "客户", "车型", "订单数量", "已出库", "计划发货", "状态", "来源锁定", "占用截止", "占用状态", "备注"), "新建销售订单"));
        definitions.put("agents", definition("代理商基础资料与联系信息", List.of("代理商编号", "代理商名称", "联系人", "联系电话", "状态", "更新时间"), "新增代理商"));
        definitions.put("trace", definition("通过车架 SN、部件 SN 或总二维码查看完整链路", List.of("查询对象", "整车 / 车架", "当前状态", "最近操作", "订单号", "客户", "查询时间"), "开始追溯查询"));
        definitions.put("parts", definition("零部件来料、质检和绑定状态", List.of("零部件 SN", "类型", "供应商", "批次", "绑定状态", "当前车架", "状态"), "新增零部件"));
        definitions.put("customers", definition("销售客户和订单归属管理", List.of("客户编号", "客户名称", "联系人", "联系电话", "状态", "创建时间"), "新增客户"));
        definitions.put("suppliers", definition("零部件供应商资料", List.of("供应商编号", "供应商名称", "联系人", "联系电话", "状态", "创建时间"), "新增供应商"));
        definitions.put("models", definition("整车车型和锁库存业务维度", List.of("车型编码", "车型名称", "启用状态", "更新时间"), "新增车型"));
        definitions.put("warehouses", definition("仓库与库位基础资料", List.of("仓库", "库位", "库位编码", "状态", "更新时间"), "新增仓库"));
        definitions.put("users", definition("系统账号与登录状态", List.of("用户名", "姓名", "账号类型", "启用状态", "最近登录"), "新增用户"));
        definitions.put("roles", definition("角色和权限配置", List.of("角色编码", "角色名称", "权限数量", "启用状态", "更新时间"), "新增角色"));
        definitions.put("workstations", definition("PDA 工位与操作范围配置", List.of("工位编码", "工位名称", "工位类型", "启用状态", "更新时间"), "新增工位"));
        definitions.put("logs", definition("关键业务操作审计记录", List.of("时间", "操作", "业务对象", "操作人", "业务类型", "备注"), "导出操作日志"));
        definitions.put("customs", definition("已出库整车报关数据导出", List.of("车架 SN", "车型", "总二维码", "客户", "订单号", "出库时间", "报关状态"), "导出报关数据"));
        definitions.put("locations", definition("仓库库位、启用状态和库存承载位置", List.of("仓库", "仓库编码", "库位", "库位编码", "状态", "更新时间"), "新增库位"));
        definitions.put("inventory-locks", definition("代理商按车型和数量锁定的库存", List.of("锁定单号", "代理商", "车型", "锁定数量", "已释放", "已转订单", "到期时间", "状态"), "创建库存锁定"));
        definitions.put("rework", definition("返工原因、换件记录和操作历史", List.of("返工单号", "车架 SN", "返工原因", "状态", "操作人", "操作时间"), "创建返工记录"));
        definitions.put("operation-logs", definitions.get("logs"));
        definitions.put("config", definition("扫码、锁库和系统运行参数", List.of("配置键", "配置值", "说明", "更新时间"), "刷新配置"));
        definitions.put("agent-dashboard", definition("代理商锁定历史与来源订单；到期和释放记录保留，当前授权车型请查看代理商库存", List.of("代理商", "车型", "有效临时锁定", "有效期", "关联订单数", "创建人", "状态", "锁定单号"), "刷新概览"));
        definitions.put("agent-inventory", definitions.get("inventory"));
        definitions.put("agent-locks", definitions.get("inventory-locks"));
        definitions.put("purchase-intents", definition("代理商采购意向与后续订单线索", List.of("代理商", "授权车型", "意向数量", "有效期", "状态"), "新增采购意向"));
        return definitions;
    }

    private static ModuleDefinition definition(String subtitle, List<String> columns, String action) {
        return new ModuleDefinition(subtitle, columns, action);
    }

    private record ModuleDefinition(String subtitle, List<String> columns, String action) {
        List<ModulePageResponse.Stat> stats(long count) {
            String value = String.valueOf(count);
            return List.of(new ModulePageResponse.Stat("查询结果", value, "blue"),
                    new ModulePageResponse.Stat("查询日期", LocalDate.now().toString(), "green"));
        }
    }
}
