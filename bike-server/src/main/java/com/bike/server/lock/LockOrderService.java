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
    @org.springframework.beans.factory.annotation.Autowired private SkuLockBatchService lockBatches;
    public LockOrderService(InventoryLockMapper locks,OrderMapper orders,OrderReservationMapper reservations,ProductionGuard guard){this.locks=locks;this.orders=orders;this.reservations=reservations;this.guard=guard;}
    public PageResponse<SalesOrderView> list(long id,long page,long pageSize) {
        CurrentOperator.requireWebAccess();
        CurrentOperator.requireAnyPermission("AGENT_INVENTORY_READ","AGENT_LOCK_WRITE");
        CurrentOperator.requirePermission("ORDER_READ");
        InventoryLockView lock=locks.selectById(id);
        if(lock==null)throw new BusinessException("INVENTORY_LOCK_NOT_FOUND","库存锁定不存在");
        customerScope.requireCustomer(lock.getCustomerId());
        if(CurrentOperator.hasRole("WEB_AGENT")&&!java.util.Objects.equals(CurrentOperator.agentId(),lock.getAgentId()))
            throw new BusinessException("FORBIDDEN","无权查看其他客户锁定的订单");
        long p=Math.min(10000000,Math.max(1,page)),size=Math.min(100,Math.max(1,pageSize));
        var records=reservations.ordersForLock(id,(p-1)*size,size);
        records.forEach(OrderServiceImpl::redactReservationForAgent);
        return new PageResponse<>(records,p,size,reservations.countForLock(id));
    }
    /** 锁定页直接转单：只允许内销购销合同。 */
    @Transactional
    public SalesOrderView convert(long id,LockOrderRequest request){
        // 转单前必须明确去向；系统不能凭客户或币种猜测内外销。
        if(request.salesType()==null)
            throw new BusinessException("ORDER_SALES_TYPE_REQUIRED","请选择转单去向：内销购销合同、外销 PI 或欧洲 PI");
        if(!SalesType.DOMESTIC.equals(request.salesType()))
            throw new BusinessException("LOCK_CONVERT_USE_PI","外销及欧洲业务请在对应菜单中从 SKU 锁定生成 PI，不在此直接生成订单");
        return createOrder(id,request,SalesType.DOMESTIC);
    }
    /** 锁定转 PI 时由 PI 转换在同一事务内生成履约订单，销售类型取自 PI 的销售入口。 */
    @Transactional
    public SalesOrderView convertForPi(long id,LockOrderRequest request,String salesType){
        return createOrder(id,request,SalesType.require(salesType));
    }
    private SalesOrderView createOrder(long id,LockOrderRequest request,String salesType){
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
        if(lock.getLockRequestId()!=null)
            lockBatches.requireWholeBatch(java.util.List.of(lock),java.util.Map.of(id,request.quantity()));
        if(orders.countEnabledCustomer(request.customerId())!=1||(lock.getAgentId()!=null&&orders.countEnabledAgent(lock.getAgentId())!=1)||orders.countEnabledModel(lock.getProductModelId())!=1)
            throw new BusinessException("INVALID_PARAMETER","订单客户、所属客户或车型不存在或已停用");
        if(lock.getSkuId()!=null && skuInventory.lockSkuModel(lock.getSkuId())==null)
            throw new BusinessException("SKU_NOT_FOUND","SKU或车型不存在或已停用");
        if(lock.getSkuId()==null && reservations.snapshot(lock.getProductModelId(),0).availableForOrder()<0)
            throw new BusinessException("INVENTORY_LOCK_CONFLICT","合格库存不足以支撑现有占用，请处理隔离或补足库存");
        if(locks.convert(id,request.quantity())!=1)throw new BusinessException("INVENTORY_LOCK_CONFLICT","转换数量超过剩余量或锁定已关闭");
        String no="SO"+UUID.randomUUID().toString().replace("-","").substring(0,18).toUpperCase();
        var orderRequest=new SalesOrderCreateRequest(request.customerId(),lock.getAgentId(),lock.getProductModelId(),request.quantity(),request.plannedShipDate(),"来源锁定："+lock.getLockNo(),salesType);
        if(orders.insertOrder(no,orderRequest,CurrentOperator.id())!=1||orders.insertItem(no,orderRequest)!=1)throw new BusinessException("ORDER_CREATE_FAILED","转单失败");
        SalesOrderView order=orders.selectByNo(no);
        if(reservations.insert(order.getId(),id,request.requestId(),lock.getProductModelId(),request.quantity(),lock.getExpireTime())!=1)throw new BusinessException("ORDER_CREATE_FAILED","订单占用保存失败");
        if(lock.getSkuId()!=null && (skuInventory.markSkuScope(order.getId())!=1
                ||skuInventory.insertReservationItem(order.getId(),lock.getSkuId(),request.quantity())!=1))
            throw new BusinessException("ORDER_CREATE_FAILED","固定SKU订单占用保存失败");
        if(orders.insertOperationLog("ORDER_CREATE",no,"锁定转入订单",CurrentOperator.id(),CurrentOperator.username(),null,"DRAFT",CurrentOperator.clientIp(),"来源："+lock.getLockNo())!=1
                || locks.insertOperationLog(lock.getLockNo(),"库存锁定转订单",CurrentOperator.id(),CurrentOperator.username(),"订单："+no+"，数量："+request.quantity(),CurrentOperator.clientIp())!=1)
            throw new BusinessException("ORDER_AUDIT_FAILED","来源锁定转换审计失败");
        return orders.selectById(order.getId());
    }
}
