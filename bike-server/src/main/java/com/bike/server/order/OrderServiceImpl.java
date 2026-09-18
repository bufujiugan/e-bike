package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.PageResponse;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {
    private final OrderMapper orderMapper;
    public OrderServiceImpl(OrderMapper orderMapper){this.orderMapper=orderMapper;}
    @Override public PageResponse<SalesOrderView> list(String keyword,String status,long page,long pageSize){CurrentOperator.requireAnyPermission("ORDER_READ","STOCK_OUT_OPERATE");long p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize));String k=keyword==null||keyword.isBlank()?null:keyword.trim();Long agentId=CurrentOperator.hasRole("WEB_AGENT")?CurrentOperator.agentId():null;if(CurrentOperator.hasRole("WEB_AGENT")&&agentId==null)throw new BusinessException("AGENT_NOT_BOUND","当前账号未绑定代理商");return new PageResponse<>(orderMapper.selectPage(k,status,agentId,(p-1)*s,s),p,s,orderMapper.count(k,status,agentId));}
    @Override public SalesOrderView get(long id){CurrentOperator.requirePermission("ORDER_READ");SalesOrderView v=orderMapper.selectById(id);if(v==null)throw new BusinessException("ORDER_NOT_FOUND","订单不存在");checkAgentScope(v);return enrichDetails(v);}
    @Override public SalesOrderView getByNo(String orderNo){CurrentOperator.requirePermission("ORDER_READ");if(orderNo==null||orderNo.isBlank())throw new BusinessException("ORDER_INVALID","订单号不能为空");SalesOrderView v=orderMapper.selectByNo(orderNo.trim());if(v==null)throw new BusinessException("ORDER_NOT_FOUND","订单不存在");checkAgentScope(v);return enrichDetails(v);}
    @Override @Transactional public SalesOrderView create(SalesOrderCreateRequest request){CurrentOperator.requirePermission("ORDER_WRITE");validateReferences(request);String no="SO"+UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase();Long operatorId=CurrentOperator.id();if(orderMapper.insertOrder(no,request,operatorId)!=1||orderMapper.insertItem(no,request)!=1)throw new BusinessException("ORDER_CREATE_FAILED","订单创建失败");orderMapper.insertOperationLog("ORDER_CREATE",no,"创建订单",operatorId,CurrentOperator.username(),null,"DRAFT",CurrentOperator.clientIp(),request.remark());return orderMapper.selectByNo(no);}
    @Override @Transactional public SalesOrderView update(long id, SalesOrderCreateRequest request){CurrentOperator.requirePermission("ORDER_WRITE");SalesOrderView current=get(id);if(!"DRAFT".equals(current.getStatus())&&!"PENDING".equals(current.getStatus()))throw new BusinessException("INVALID_STATUS_TRANSITION","只有草稿或待确认订单可编辑");if(current.getShippedQty()>request.orderQty())throw new BusinessException("ORDER_QTY_INVALID","订单数量不能小于已出库数量");validateReferences(request);if(orderMapper.updateOrder(id,request)!=1||orderMapper.updateItem(id,request)!=1)throw new BusinessException("ORDER_UPDATE_FAILED","订单更新失败");orderMapper.insertOperationLog("ORDER_UPDATE",current.getOrderNo(),"更新订单",CurrentOperator.id(),CurrentOperator.username(),current.getStatus(),current.getStatus(),CurrentOperator.clientIp(),request.remark());return get(id);}
    @Override @Transactional public SalesOrderView confirm(long id){CurrentOperator.requirePermission("ORDER_WRITE");CurrentOperator.requireWebAccess();SalesOrderView o=get(id);if(!"DRAFT".equals(o.getStatus())&& !"PENDING".equals(o.getStatus()))throw new BusinessException("INVALID_STATUS_TRANSITION","订单当前不可确认");if(orderMapper.updateStatus(id,"CONFIRMED")!=1)throw new BusinessException("ORDER_UPDATE_FAILED","订单确认失败");orderMapper.insertOperationLog("ORDER_UPDATE",o.getOrderNo(),"确认订单",CurrentOperator.id(),CurrentOperator.username(),o.getStatus(),"CONFIRMED",CurrentOperator.clientIp(),null);return get(id);}
    @Override @Transactional public SalesOrderView cancel(long id,String reason){CurrentOperator.requirePermission("ORDER_WRITE");CurrentOperator.requireWebAccess();String normalizedReason=reason==null||reason.isBlank()?"业务作废":reason.trim();if(normalizedReason.length()>500)throw new BusinessException("INVALID_PARAMETER","作废原因长度不能超过 500");SalesOrderView o=get(id);if("SHIPPED".equals(o.getStatus())||"PARTIAL_SHIPPED".equals(o.getStatus()))throw new BusinessException("INVALID_STATUS_TRANSITION","已有出库记录的订单不可直接作废");if(orderMapper.updateStatus(id,"CANCELLED")!=1)throw new BusinessException("ORDER_UPDATE_FAILED","订单作废失败");orderMapper.insertOperationLog("ORDER_UPDATE",o.getOrderNo(),"作废订单",CurrentOperator.id(),CurrentOperator.username(),o.getStatus(),"CANCELLED",CurrentOperator.clientIp(),normalizedReason);return get(id);}
    private static void checkAgentScope(SalesOrderView order){if(CurrentOperator.hasRole("WEB_AGENT")&&!java.util.Objects.equals(CurrentOperator.agentId(),order.getAgentId()))throw new BusinessException("FORBIDDEN","无权访问其他代理商订单");}
    private void validateReferences(SalesOrderCreateRequest request) {
        if (orderMapper.countEnabledCustomer(request.customerId()) != 1) {
            throw new BusinessException("CUSTOMER_NOT_FOUND", "客户不存在或已停用");
        }
        if (orderMapper.countEnabledModel(request.modelId()) != 1) {
            throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        }
        if (request.agentId() != null && orderMapper.countEnabledAgent(request.agentId()) != 1) {
            throw new BusinessException("AGENT_NOT_FOUND", "代理商不存在或已停用");
        }
    }
    private SalesOrderView enrichDetails(SalesOrderView order) {
        order.setShippedVehicles(orderMapper.selectShippedVehicles(order.getId()));
        return order;
    }
}
