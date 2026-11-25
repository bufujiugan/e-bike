package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.CustomerScopeAccess;
import com.bike.server.lock.InventoryLockMapper;
import com.bike.server.lock.InventoryLockView;
import com.bike.server.lock.SkuLockMapper;
import com.bike.server.production.ProductionGuard;
import com.bike.server.stock.SkuInventoryMapper;
import com.bike.server.system.SystemConfigMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomesticSkuOrderServiceImpl implements DomesticSkuOrderService {
    private final DomesticSkuOrderMapper domesticOrders;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final InventoryLockMapper locks;
    private final SkuInventoryMapper inventory;
    private final SkuLockMapper skuLocks;
    private final ProductionGuard guard;
    private final CustomerScopeAccess customerScope;
    private final SystemConfigMapper config;
    private final ObjectMapper json;
    private final boolean creationEnabled;

    public DomesticSkuOrderServiceImpl(DomesticSkuOrderMapper domesticOrders, OrderMapper orders,
            OrderReservationMapper reservations, InventoryLockMapper locks, SkuInventoryMapper inventory,
            SkuLockMapper skuLocks, ProductionGuard guard, CustomerScopeAccess customerScope,
            SystemConfigMapper config, ObjectMapper json,
            @Value("${bike.orders.domestic-sku-creation-enabled:true}") boolean creationEnabled) {
        this.domesticOrders = domesticOrders;
        this.orders = orders;
        this.reservations = reservations;
        this.locks = locks;
        this.inventory = inventory;
        this.skuLocks = skuLocks;
        this.guard = guard;
        this.customerScope = customerScope;
        this.config = config;
        this.json = json;
        this.creationEnabled = creationEnabled;
    }

    @Override
    @Transactional
    public SalesOrderView create(DomesticSkuOrderRequest input) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
        var items = input.items() == null ? List.<DomesticSkuOrderRequest.Item>of()
                : input.items().stream().sorted(Comparator.comparingLong(DomesticSkuOrderRequest.Item::skuId)).toList();
        var sources = input.sources() == null ? List.<DomesticSkuOrderRequest.Source>of()
                : input.sources().stream().sorted(Comparator.comparingLong(DomesticSkuOrderRequest.Source::lockId)).toList();
        if (items.isEmpty() == sources.isEmpty()) {
            throw new BusinessException("INVALID_PARAMETER", "请选择订单 SKU 或来源锁定");
        }
        if (!sources.isEmpty()) CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        var request = new DomesticSkuOrderRequest(input.requestId().strip(), input.customerId(), input.agentId(),
                input.plannedShipDate(), input.remark(), OrderTransportRequest.validateArrangement(input.transportArrangement()),
                OrderTransportRequest.normalizeRemark(input.transportRemark()), items, sources);
        guard.lock();
        customerScope.requireCustomer(request.customerId());
        long operatorId = CurrentOperator.id();
        String hash = hash(request);
        var existing = domesticOrders.findRequest(operatorId, request.requestId());
        if (existing != null) {
            if (!hash.equals(existing.payloadHash())) {
                throw new BusinessException("ORDER_REQUEST_CONFLICT", "同一请求标识不能用于不同订单内容");
            }
            return orders.selectById(existing.orderId());
        }
        if (!creationEnabled) throw new BusinessException("DOMESTIC_SKU_CREATION_DISABLED", "内销 SKU 建单入口暂未开放");
        if (orders.countEnabledCustomer(request.customerId()) != 1
                || request.agentId() != null && orders.countEnabledAgent(request.agentId()) != 1) {
            throw new BusinessException("INVALID_PARAMETER", "客户或代理商不存在或已停用");
        }
        LocalDateTime now = skuLocks.now();
        var quantities = new TreeMap<Long, Integer>();
        for (var item : items) {
            if (quantities.putIfAbsent(item.skuId(), item.quantity()) != null) {
                throw new BusinessException("INVALID_PARAMETER", "同一 SKU 请合并填写数量");
            }
        }
        var selectedLocks = selectSources(request, now, quantities);
        var models = new TreeMap<Long, Integer>();
        for (var entry : quantities.entrySet()) {
            Long modelId = inventory.lockSkuModel(entry.getKey());
            if (modelId == null) throw new BusinessException("SKU_NOT_FOUND", "SKU 或车型不存在或已停用");
            models.merge(modelId, entry.getValue(), Integer::sum);
            // SKU 预锁可先登记意向。手工新建直接占用实际可用库存。
            if (selectedLocks.isEmpty() && inventory.snapshot(entry.getKey(), 0).availableForOrder() < entry.getValue()) {
                throw new BusinessException("SKU_INVENTORY_NOT_ENOUGH", "所选 SKU 可用库存不足");
            }
        }
        if (selectedLocks.isEmpty()) {
            for (var model : models.entrySet()) {
                if (reservations.snapshot(model.getKey(), 0).availableForOrder() < model.getValue()) {
                    throw new BusinessException("INVENTORY_NOT_ENOUGH", "所选 SKU 合计超过车型可用库存");
                }
            }
        }
        return save(request, now, quantities, models.firstKey(), selectedLocks, operatorId, hash);
    }

    @Override
    public boolean isCreationEnabled() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
        return creationEnabled;
    }

    @Override
    public List<DomesticSkuOption> skuOptions(String keyword) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
        return domesticOrders.selectSkuOptions(keyword);
    }

    @Override
    public List<InventoryLockView> sources(long id, String keyword) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
        CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        InventoryLockView origin = locks.selectById(id);
        if (origin == null || origin.getSkuId() == null) {
            throw new BusinessException("INVENTORY_LOCK_NOT_FOUND", "SKU 锁定不存在");
        }
        customerScope.requireCustomer(origin.getCustomerId());
        return locks.selectConvertibleSources(id, keyword);
    }

    private List<DomesticOrderLockSource> selectSources(DomesticSkuOrderRequest request, LocalDateTime now,
            TreeMap<Long, Integer> quantities) {
        var selected = new ArrayList<DomesticOrderLockSource>();
        long previousLockId = 0;
        for (var source : request.sources()) {
            if (source.lockId() == previousLockId) {
                throw new BusinessException("INVALID_PARAMETER", "同一锁定单只能选择一次");
            }
            previousLockId = source.lockId();
            InventoryLockView lock = locks.selectById(source.lockId());
            if (lock == null || lock.getSkuId() == null) {
                throw new BusinessException("INVENTORY_LOCK_NOT_FOUND", "有效的 SKU 锁定不存在");
            }
            if (!Objects.equals(lock.getCustomerId(), request.customerId())
                    || !Objects.equals(lock.getAgentId(), request.agentId())) {
                throw new BusinessException("LOCK_CUSTOMER_MISMATCH", "合并转单须选择同一客户、同一代理商的锁定");
            }
            if (lock.getExpireTime() == null || !lock.getExpireTime().isAfter(now)) {
                throw new BusinessException("INVENTORY_LOCK_EXPIRED", "锁定 " + lock.getLockNo() + " 已到期");
            }
            selected.add(new DomesticOrderLockSource(source.lockId(), lock.getLockNo(), lock.getSkuId(),
                    source.quantity(), lock.getExpireTime()));
            quantities.merge(lock.getSkuId(), source.quantity(), Integer::sum);
        }
        return selected;
    }

    private SalesOrderView save(DomesticSkuOrderRequest request, LocalDateTime now, TreeMap<Long, Integer> quantities,
            long summaryModelId, List<DomesticOrderLockSource> sources, long operatorId, String hash) {
        String no = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase(java.util.Locale.ROOT);
        int total = quantities.values().stream().mapToInt(Integer::intValue).sum();
        var summary = new SalesOrderCreateRequest(request.customerId(), request.agentId(), summaryModelId, total,
                request.plannedShipDate(), request.remark(), SalesType.DOMESTIC, request.transportArrangement(),
                request.transportRemark());
        if (orders.insertOrder(no, summary, operatorId) != 1 || orders.insertItem(no, summary) != 1) {
            throw new BusinessException("ORDER_CREATE_FAILED", "内销订单创建失败");
        }
        // 订单头记录最后一笔截止，库存占用及出库按每笔来源的有效截止计算。
        var expiresAt = sources.stream().map(DomesticOrderLockSource::expiresAt).max(Comparator.naturalOrder())
                .orElseGet(() -> now.plusHours(defaultHoldHours()));
        long orderId = orders.selectByNo(no).getId();
        if (reservations.insertDomesticSku(orderId, request.requestId(), summaryModelId, total, expiresAt) != 1
                || domesticOrders.insertRequest(orderId, operatorId, request.requestId(), hash) != 1) {
            throw new BusinessException("ORDER_CREATE_FAILED", "订单占用保存失败");
        }
        for (var entry : quantities.entrySet()) {
            if (inventory.insertDomesticReservationItem(orderId, entry.getKey(), entry.getValue()) != 1) {
                throw new BusinessException("ORDER_CREATE_FAILED", "订单 SKU 明细保存失败");
            }
        }
        for (var source : sources) {
            if (locks.convert(source.lockId(), source.quantity()) != 1) {
                throw new BusinessException("INVENTORY_LOCK_CONFLICT", "锁定 " + source.lockNo() + " 余额不足或已关闭");
            }
            if (domesticOrders.insertSource(orderId, source) != 1
                    || locks.insertOperationLog(source.lockNo(), "库存锁定转订单", operatorId, CurrentOperator.username(),
                            "订单：" + no + "，数量：" + source.quantity(), CurrentOperator.clientIp()) != 1) {
                throw new BusinessException("ORDER_AUDIT_FAILED", "来源锁定审计保存失败");
            }
        }
        if (orders.insertOperationLog("ORDER_CREATE", no, "创建内销 SKU 订单", operatorId, CurrentOperator.username(),
                null, "DRAFT", CurrentOperator.clientIp(), request.remark()) != 1) {
            throw new BusinessException("ORDER_AUDIT_FAILED", "订单审计保存失败");
        }
        return orders.selectById(orderId);
    }

    private long defaultHoldHours() {
        String configured = config.selectValue("inventory.lock.hours");
        if (configured == null || !configured.matches("[0-9]{1,3}")) return 48;
        long hours = Long.parseLong(configured);
        return hours > 0 && hours <= 720 ? hours : 48;
    }

    private String hash(DomesticSkuOrderRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("计算内销订单请求指纹失败", error);
        }
    }
}
