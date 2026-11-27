package com.bike.server.lock;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CustomerScopeAccess;
import com.bike.server.common.PageResponse;
import com.bike.server.common.CurrentOperator;
import com.bike.server.customer.CustomerPortalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class InventoryLockServiceImpl implements InventoryLockService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private static final Logger log = LoggerFactory.getLogger(InventoryLockServiceImpl.class);
    private final InventoryLockMapper mapper;
    @Autowired private com.bike.server.production.ProductionGuard guard;
    @Autowired private CustomerPortalService customerPortal;
    @Autowired private SkuLockMapper lockRequests;
    private final com.bike.server.system.SystemConfigMapper configMapper;
    @Autowired
    public InventoryLockServiceImpl(InventoryLockMapper mapper, com.bike.server.system.SystemConfigMapper configMapper){this.mapper=mapper;this.configMapper=configMapper;}
    public InventoryLockServiceImpl(InventoryLockMapper mapper){this(mapper,null);}
    @Override public InventoryLockView getByNo(String lockNo) {
        CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ", "AGENT_LOCK_WRITE");
        CurrentOperator.requireWebAccess();
        if (lockNo == null || lockNo.isBlank()) throw new BusinessException("INVALID_PARAMETER", "锁定单号不能为空");
        InventoryLockView record = mapper.selectByNo(lockNo.trim());
        if (record == null) throw new BusinessException("INVENTORY_LOCK_NOT_FOUND", "库存锁定不存在");
        checkAgentScope(record);
        return record;
    }
    @Override public PageResponse<InventoryLockView> list(Long agentId,long page,long pageSize){
        CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ", "AGENT_LOCK_WRITE");
        CurrentOperator.requireWebAccess();
        Long scopedAgent = CurrentOperator.hasRole("WEB_AGENT") ? requireAgent() : agentId;
        long p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.selectPage(scopedAgent,(p-1)*s,s),p,s,mapper.count(scopedAgent));
    }
    @Override
    @Transactional
    public InventoryLockView create(InventoryLockCreateRequest request) {
        CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        CurrentOperator.requireWebAccess();
        boolean isAgent = CurrentOperator.hasRole("WEB_AGENT");
        if (!isAgent) CustomerScopeAccess.requireAll();
        Long agentId = isAgent ? requireAgent() : request.agentId();
        if (agentId == null || agentId <= 0) throw new BusinessException("AGENT_REQUIRED", "请选择所属客户");
        if (guard != null) guard.lock();
        requireModelStock(request.productModelId(), request.lockQty(), isAgent ? agentId : null);
        LocalDateTime now = mapper.selectDatabaseTime();
        LocalDateTime expireTime = isAgent || request.expireTime() == null
                ? now.plusHours(defaultLockHours()) : request.expireTime();
        if (!expireTime.isAfter(now)) throw new BusinessException("INVALID_PARAMETER", "锁定有效期必须晚于当前时间");
        var scopedRequest = new InventoryLockCreateRequest(agentId, request.productModelId(), request.lockQty(),
                expireTime, request.remark());
        String lockNo = newLockNo();
        Long operatorId = CurrentOperator.id();
        if (mapper.insert(lockNo, scopedRequest, expireTime, operatorId) != 1
                || mapper.insertDetail(lockNo, request.productModelId(), request.lockQty()) != 1) {
            throw lockCreationFailed();
        }
        recordLock(lockNo, request.productModelId(), request.lockQty(), operatorId);
        return mapper.selectById(mapper.selectIdByNo(lockNo));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerLockReceipt lockCustomerInventory(CustomerLockRequest request) {
        CurrentOperator.requireWebAccess();
        CurrentOperator.requireAnyRole("WEB_CUSTOMER", "WEB_AGENT");
        boolean isCustomer = CurrentOperator.hasRole("WEB_CUSTOMER");
        if (!isCustomer) CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        Long operatorId = CurrentOperator.id();
        if (operatorId == null) throw new BusinessException("UNAUTHORIZED", "请先登录");
        Long agentId = isCustomer ? null : requireAgent();
        Long customerId = isCustomer ? customerPortal.getCompany().id() : null;

        // 锁定、到期和库存变动共用生产互斥，库存校验与写入在同一事务内完成。
        guard.lock();
        String requestId = request.requestId().strip();
        String hash = customerRequestHash(customerId, agentId, request);
        var previous = lockRequests.request(operatorId, requestId);
        if (previous != null) {
            if (!hash.equals(previous.payloadHash())) {
                throw new BusinessException("INVENTORY_LOCK_REQUEST_CONFLICT", "同一请求标识不能用于不同锁定内容");
            }
            return customerReceipt(previous.id());
        }

        requireModelStock(request.modelId(), request.quantity(), agentId);
        LocalDateTime expireTime = mapper.selectDatabaseTime().plusHours(defaultLockHours());
        if (lockRequests.insertRequest(operatorId, requestId, hash) != 1) throw lockCreationFailed();
        long lockRequestId = lockRequests.request(operatorId, requestId).id();
        String lockNo = newLockNo();
        if (mapper.insertCustomerLock(lockNo, customerId, agentId, request, expireTime, operatorId, lockRequestId) != 1
                || mapper.insertDetail(lockNo, request.modelId(), request.quantity()) != 1
                || recordLock(lockNo, request.modelId(), request.quantity(), operatorId) != 1) {
            throw lockCreationFailed();
        }
        return customerReceipt(lockRequestId);
    }

    private void requireModelStock(long modelId, int quantity, Long agentId) {
        if (agentId != null && mapper.lockAgentAccess(agentId) == null) {
            throw new BusinessException("AGENT_NOT_FOUND", "客户不存在或已停用");
        }
        if (!Integer.valueOf(1).equals(mapper.lockModelForUpdate(modelId))) {
            throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        }
        if (agentId != null && mapper.countAuthorizedModel(agentId, modelId) == 0) {
            throw new BusinessException("MODEL_NOT_AUTHORIZED", "当前客户无权使用该车型");
        }
        long physical = mapper.selectPhysicalForUpdate(modelId);
        long committed = mapper.selectActiveLockedForUpdate(modelId);
        if (physical - committed < quantity) {
            throw new BusinessException("INVENTORY_NOT_ENOUGH", "可用库存不足，无法锁定");
        }
    }

    private int recordLock(String lockNo, long modelId, int quantity, Long operatorId) {
        return mapper.insertOperationLog(lockNo, "库存锁定", operatorId, CurrentOperator.username(),
                "车型:" + modelId + ",数量:" + quantity, CurrentOperator.clientIp());
    }

    private static String newLockNo() {
        return "LK" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private static BusinessException lockCreationFailed() {
        return new BusinessException("INVENTORY_LOCK_CREATE_FAILED", "库存锁定创建失败");
    }

    private CustomerLockReceipt customerReceipt(long lockRequestId) {
        var lock = mapper.selectCustomerLock(lockRequestId);
        if (lock == null) throw new BusinessException("INVENTORY_LOCK_NOT_FOUND", "库存锁定不存在");
        return new CustomerLockReceipt(lock.lockNo(), Instant.ofEpochMilli(lock.expiresAtMillis()));
    }

    private static String customerRequestHash(Long customerId, Long agentId, CustomerLockRequest request) {
        String payload = "CUSTOMER_MODEL:" + customerId + ":" + agentId + ":" + request.modelId() + ":" + request.quantity();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("库存锁定请求无法计算摘要", exception);
        }
    }
    @Override
    @Transactional
    public InventoryLockView release(long id, String reason) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        CurrentOperator.requireInternalWebAccess();
        if (guard != null) guard.lock();
        InventoryLockView lock = require(id);
        checkAgentScope(lock);
        ensureNotExpired(lock);
        if (!"LOCKED".equals(lock.getStatus()) && !"PARTIAL_CONVERTED".equals(lock.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "锁定当前不可释放");
        }
        if (mapper.release(id, reason) != 1) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "释放锁定失败");
        }
        mapper.insertOperationLog(lock.getLockNo(), "释放库存锁定", CurrentOperator.id(),
                CurrentOperator.username(), reason, CurrentOperator.clientIp());
        return mapper.selectById(id);
    }
    @Override @Transactional public InventoryLockView convert(long id,int quantity){CurrentOperator.requirePermission("AGENT_LOCK_WRITE");CurrentOperator.requireWebAccess();if(quantity<=0)throw new BusinessException("INVALID_PARAMETER","转换数量必须大于 0");InventoryLockView lock=require(id);checkAgentScope(lock);ensureNotExpired(lock);int remaining=lock.getLockQty()-lock.getReleasedQty()-lock.getConvertedQty();if(quantity>remaining)throw new BusinessException("INVENTORY_LOCK_CONFLICT","转换数量超过锁定数量");throw new BusinessException("LOCK_ORDER_UPGRADE_REQUIRED","请由业务员在新版转单窗口选择客户并创建订单，旧转换接口已停用");}
    @Override public PageResponse<LegacyConversionAuditView> legacyConversions(long page,long pageSize){
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_READ");
        long p=Math.min(10_000_000,Math.max(1,page)),s=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.selectLegacyConversions((p-1)*s,s),p,s,mapper.countLegacyConversions());
    }
    private InventoryLockView require(long id){InventoryLockView v=mapper.selectById(id);if(v==null)throw new BusinessException("INVENTORY_LOCK_NOT_FOUND","库存锁定不存在");return v;}
    private Long requireAgent(){Long agentId=CurrentOperator.agentId();if(agentId==null)throw new BusinessException("AGENT_NOT_BOUND","当前账号未绑定所属客户");return agentId;}
    private void checkAgentScope(InventoryLockView lock){customerScope.requireCustomer(lock.getCustomerId());if(CurrentOperator.hasRole("WEB_AGENT")&&!requireAgent().equals(lock.getAgentId()))throw new BusinessException("FORBIDDEN","无权操作其他客户锁定记录");}
    private void ensureNotExpired(InventoryLockView lock){if(lock.getExpireTime()==null||!lock.getExpireTime().isAfter(mapper.selectDatabaseTime()))throw new BusinessException("INVENTORY_LOCK_EXPIRED","库存锁定已过期，不能继续操作");}
    private long defaultLockHours(){if(configMapper==null)return 48;try{String value=configMapper.selectValue("inventory.lock.hours");long hours=Long.parseLong(value);return hours>0&&hours<=720?hours:48;}catch(RuntimeException exception){log.warn("读取锁库存默认时长失败，使用 48 小时兜底", exception);return 48;}}
}
