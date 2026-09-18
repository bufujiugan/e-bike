package com.bike.server.lock;

import com.bike.server.common.BusinessException;
import com.bike.server.common.PageResponse;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class InventoryLockServiceImpl implements InventoryLockService {
    private static final Logger log = LoggerFactory.getLogger(InventoryLockServiceImpl.class);
    private final InventoryLockMapper mapper;
    private final com.bike.server.system.SystemConfigMapper configMapper;
    @Autowired
    public InventoryLockServiceImpl(InventoryLockMapper mapper, com.bike.server.system.SystemConfigMapper configMapper){this.mapper=mapper;this.configMapper=configMapper;}
    public InventoryLockServiceImpl(InventoryLockMapper mapper){this(mapper,null);}
    @Override public PageResponse<InventoryLockView> list(Long agentId,long page,long pageSize){
        CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ", "AGENT_LOCK_WRITE");
        CurrentOperator.requireWebAccess();
        Long scopedAgent = CurrentOperator.hasRole("WEB_AGENT") ? requireAgent() : agentId;
        long p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.selectPage(scopedAgent,(p-1)*s,s),p,s,mapper.count(scopedAgent));
    }
    @Override @Transactional public InventoryLockView create(InventoryLockCreateRequest request){CurrentOperator.requirePermission("AGENT_LOCK_WRITE");CurrentOperator.requireWebAccess();Long scopedAgent = CurrentOperator.hasRole("WEB_AGENT") ? requireAgent() : request.agentId();if(scopedAgent == null || scopedAgent <= 0)throw new BusinessException("AGENT_REQUIRED","代理商不能为空");InventoryLockCreateRequest scopedRequest = java.util.Objects.equals(scopedAgent,request.agentId()) ? request : new InventoryLockCreateRequest(scopedAgent,request.productModelId(),request.lockQty(),request.expireTime(),request.remark());if(mapper.lockModelForUpdate(scopedRequest.productModelId()) != 1)throw new BusinessException("MODEL_NOT_FOUND","车型不存在或已停用");long physical=mapper.selectPhysicalForUpdate(scopedRequest.productModelId()),locked=mapper.selectActiveLockedForUpdate(scopedRequest.productModelId());if(physical-locked<scopedRequest.lockQty())throw new BusinessException("INVENTORY_NOT_ENOUGH","可用库存不足，无法锁定");LocalDateTime expire=scopedRequest.expireTime()==null?LocalDateTime.now().plusHours(defaultLockHours()):scopedRequest.expireTime();if(!expire.isAfter(LocalDateTime.now()))throw new BusinessException("INVALID_PARAMETER","锁定有效期必须晚于当前时间");String no="LK"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();Long operatorId=CurrentOperator.id();if(mapper.insert(no,scopedRequest,expire,operatorId)!=1||mapper.insertDetail(no,scopedRequest.productModelId(),scopedRequest.lockQty())!=1)throw new BusinessException("INVENTORY_LOCK_CREATE_FAILED","库存锁定创建失败");mapper.insertOperationLog(no,"库存锁定",operatorId,CurrentOperator.username(),"车型:"+scopedRequest.productModelId()+",数量:"+scopedRequest.lockQty(),CurrentOperator.clientIp());return mapper.selectById(mapper.selectIdByNo(no));}
    @Override @Transactional public InventoryLockView release(long id,String reason){CurrentOperator.requirePermission("AGENT_LOCK_WRITE");CurrentOperator.requireWebAccess();InventoryLockView lock=require(id);checkAgentScope(lock);ensureNotExpired(lock);if(!"LOCKED".equals(lock.getStatus())&& !"PARTIAL_CONVERTED".equals(lock.getStatus()))throw new BusinessException("INVALID_STATUS_TRANSITION","锁定当前不可释放");if(mapper.release(id,reason)!=1)throw new BusinessException("INVALID_STATUS_TRANSITION","释放锁定失败");mapper.insertOperationLog(lock.getLockNo(),"释放库存锁定",CurrentOperator.id(),CurrentOperator.username(),reason,CurrentOperator.clientIp());return mapper.selectById(id);}
    @Override @Transactional public InventoryLockView convert(long id,int quantity){CurrentOperator.requirePermission("AGENT_LOCK_WRITE");CurrentOperator.requireWebAccess();if(quantity<=0)throw new BusinessException("INVALID_PARAMETER","转换数量必须大于 0");InventoryLockView lock=require(id);checkAgentScope(lock);ensureNotExpired(lock);int remaining=lock.getLockQty()-lock.getReleasedQty()-lock.getConvertedQty();if(quantity>remaining)throw new BusinessException("INVENTORY_LOCK_CONFLICT","转换数量超过锁定数量");if(mapper.convert(id,quantity)!=1)throw new BusinessException("INVALID_STATUS_TRANSITION","锁定转订单失败");mapper.insertOperationLog(lock.getLockNo(),"库存锁定转订单",CurrentOperator.id(),CurrentOperator.username(),"数量:"+quantity,CurrentOperator.clientIp());return mapper.selectById(id);}
    private InventoryLockView require(long id){InventoryLockView v=mapper.selectById(id);if(v==null)throw new BusinessException("INVENTORY_LOCK_NOT_FOUND","库存锁定不存在");return v;}
    private Long requireAgent(){Long agentId=CurrentOperator.agentId();if(agentId==null)throw new BusinessException("AGENT_NOT_BOUND","当前账号未绑定代理商");return agentId;}
    private void checkAgentScope(InventoryLockView lock){if(CurrentOperator.hasRole("WEB_AGENT")&&!requireAgent().equals(lock.getAgentId()))throw new BusinessException("FORBIDDEN","无权操作其他代理商锁定记录");}
    private void ensureNotExpired(InventoryLockView lock){if(lock.getExpireTime()==null||!lock.getExpireTime().isAfter(LocalDateTime.now()))throw new BusinessException("INVENTORY_LOCK_EXPIRED","库存锁定已过期，不能继续操作");}
    private long defaultLockHours(){if(configMapper==null)return 48;try{String value=configMapper.selectValue("inventory.lock.hours");long hours=Long.parseLong(value);return hours>0&&hours<=720?hours:48;}catch(RuntimeException exception){log.warn("读取锁库存默认时长失败，使用 48 小时兜底", exception);return 48;}}
}
