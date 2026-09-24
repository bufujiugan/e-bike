package com.bike.server.lock;

import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.production.ProductionGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class LockOrderService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final InventoryLockMapper locks;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final ProductionGuard guard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.stock.SkuInventoryMapper skuInventory;
    public LockOrderService(InventoryLockMapper locks,OrderMapper orders,OrderReservationMapper reservations,ProductionGuard guard){this.locks=locks;this.orders=orders;this.reservations=reservations;this.guard=guard;}
    public PageResponse<SalesOrderView> list(long id,long page,long pageSize) {
        CurrentOperator.requireWebAccess();
        CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ","AGENT_LOCK_WRITE");
        CurrentOperator.requirePermission("ORDER_READ");
        InventoryLockView lock=locks.selectById(id);
        if(lock==null)throw new BusinessException("INVENTORY_LOCK_NOT_FOUND","库存锁定不存在");
        customerScope.requireCustomer(lock.getCustomerId());
        if(CurrentOperator.hasRole("WEB_AGENT")&&!java.util.Objects.equals(CurrentOperator.agentId(),lock.getAgentId()))
            throw new BusinessException("FORBIDDEN","无权查看其他代理商锁定的订单");
        long p=Math.min(10000000,Math.max(1,page)),size=Math.min(100,Math.max(1,pageSize));
        var records=reservations.ordersForLock(id,(p-1)*size,size);
        records.forEach(OrderServiceImpl::redactReservationForAgent);
        return new PageResponse<>(records,p,size,reservations.countForLock(id));
    }
    @Transactional
    public SalesOrderView convert(long id,LockOrderRequest request){
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("AGENT_LOCK_WRITE");CurrentOperator.requirePermission("ORDER_WRITE");
        customerScope.requireCustomer(request.customerId());
        guard.lock();
        InventoryLockView lock=locks.selectById(id);
        if(lock==null)throw new BusinessException("INVENTORY_LOCK_NOT_FOUND","库存锁定不存在");
        customerScope.requireCustomer(lock.getCustomerId());
        Long existing=reservations.findOrder(id,request.requestId());
        if(existing!=null){
            SalesOrderView previous=orders.selectById(existing);
            if(!request.customerId().equals(previous.getCustomerId())||request.quantity()!=previous.getOrderQty()
                ||!java.util.Objects.equals(request.plannedShipDate(),previous.getPlannedShipDate()))
                throw new BusinessException("INVENTORY_LOCK_CONFLICT","同一请求标识不能用于不同转单内容");
            return previous;
        }
        if(lock.getExpireTime()==null||!lock.getExpireTime().isAfter(LocalDateTime.now()))throw new BusinessException("INVENTORY_LOCK_EXPIRED","锁定已到期");
        if(lock.getCustomerId()!=null&&!lock.getCustomerId().equals(request.customerId()))
            throw new BusinessException("LOCK_CUSTOMER_MISMATCH","SKU锁定必须转入原客户的订单");
        if(orders.countEnabledCustomer(request.customerId())!=1||(lock.getAgentId()!=null&&orders.countEnabledAgent(lock.getAgentId())!=1)||orders.countEnabledModel(lock.getProductModelId())!=1)
            throw new BusinessException("INVALID_PARAMETER","客户、代理商或车型不存在或已停用");
        if(lock.getSkuId()!=null && (skuInventory.lockSkuModel(lock.getSkuId())==null || skuInventory.snapshot(lock.getSkuId(),0).availableForOrder()<0))
            throw new BusinessException("SKU_INVENTORY_NOT_ENOUGH","该SKU合格库存不足以支撑现有锁定，请处理隔离或补足库存");
        if(reservations.snapshot(lock.getProductModelId(),0).availableForOrder()<0)
            throw new BusinessException("INVENTORY_LOCK_CONFLICT","合格库存不足以支撑现有占用，请处理隔离或补足库存");
        if(locks.convert(id,request.quantity())!=1)throw new BusinessException("INVENTORY_LOCK_CONFLICT","转换数量超过剩余量或锁定已关闭");
        String no="SO"+UUID.randomUUID().toString().replace("-","").substring(0,18).toUpperCase();
        var orderRequest=new SalesOrderCreateRequest(request.customerId(),lock.getAgentId(),lock.getProductModelId(),request.quantity(),request.plannedShipDate(),"来源锁定："+lock.getLockNo());
        if(orders.insertOrder(no,orderRequest,CurrentOperator.id())!=1||orders.insertItem(no,orderRequest)!=1)throw new BusinessException("ORDER_CREATE_FAILED","转单失败");
        SalesOrderView order=orders.selectByNo(no);
        if(reservations.insert(order.getId(),id,request.requestId(),lock.getProductModelId(),request.quantity(),lock.getExpireTime())!=1)throw new BusinessException("ORDER_CREATE_FAILED","订单占用保存失败");
        if(lock.getSkuId()!=null && (skuInventory.markSkuScope(order.getId())!=1
                ||skuInventory.insertReservationItem(order.getId(),lock.getSkuId(),request.quantity())!=1))
            throw new BusinessException("ORDER_CREATE_FAILED","固定SKU订单占用保存失败");
        orders.insertOperationLog("ORDER_CREATE",no,"锁定转入订单",CurrentOperator.id(),CurrentOperator.username(),null,"DRAFT",CurrentOperator.clientIp(),"来源："+lock.getLockNo());
        locks.insertOperationLog(lock.getLockNo(),"库存锁定转订单",CurrentOperator.id(),CurrentOperator.username(),"订单："+no+"，数量："+request.quantity(),CurrentOperator.clientIp());
        return orders.selectById(order.getId());
    }
}
