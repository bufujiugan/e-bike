package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.order.OrderMapper;
import com.bike.server.production.ProductionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiLogisticsService {
    private final PiLogisticsMapper mapper;
    private final PiMapper pis;
    private final PiSeriesMapper series;
    private final PiDispatchMapper batches;
    private final OrderMapper orders;
    private final ProductionGuard guard;
    private final OperationLogMapper logs;
    private final Validator validator;
    private final ObjectMapper json;
    public PiLogisticsService(PiLogisticsMapper mapper,PiMapper pis,PiSeriesMapper series,PiDispatchMapper batches,
            OrderMapper orders,ProductionGuard guard,OperationLogMapper logs,Validator validator,ObjectMapper json) {
        this.mapper=mapper;this.pis=pis;this.series=series;this.batches=batches;this.orders=orders;
        this.guard=guard;this.logs=logs;this.validator=validator;this.json=json;
    }
    @Transactional
    public long save(long piId,Long logisticsId,PiLogisticsRequest input) {
        requireWrite();validate(input);
        if(text(input.requestId())==null)fail("LOGISTICS_REQUEST_REQUIRED","物流操作需要请求标识，请升级客户端");
        var request=new PiLogisticsRequest(input.batchNo().trim(),text(input.carrier()),text(input.trackingNo()),
                text(input.status())==null?"PLANNED":input.status().trim().toUpperCase(Locale.ROOT),text(input.remark()),
                input.stockOutBatchId(),input.requestId().trim(),input.expectedRevision());
        if(!Set.of("PLANNED","SHIPPED").contains(request.status()))fail("LOGISTICS_STATUS_INVALID","仅可保存计划或确认实际发运");
        PiView pi=lock(piId);String hash=hash(Map.of("operation",logisticsId==null?"CREATE":"UPDATE","id",logisticsId==null?0:logisticsId,"request",request));
        var prior=mapper.previous(piId,CurrentOperator.id(),request.requestId());
        if(prior!=null){same(prior,hash);return prior.logisticsId();}
        if(!Set.of("CONFIRMED","RELEASED").contains(pi.getStatus()))fail("INVALID_STATUS_TRANSITION","当前PI不能登记物流");
        PiLogisticsView before=logisticsId==null?null:require(piId,logisticsId);
        if(before!=null&&(!Objects.equals(before.getRevision(),request.expectedRevision())||"SHIPPED".equals(before.getStatus())))
            fail("LOGISTICS_REVISION_CONFLICT","物流已变更或已发运；已发运记录须先撤回后修改");
        PiDispatchBatch batch=null;
        if(request.stockOutBatchId()!=null) {
            batch=batches.get(request.stockOutBatchId());
            if(batch==null||!Objects.equals(series.seriesId(piId),batch.seriesId())||batch.customerId()!=pi.getCustomerId())fail("PI_BATCH_MISMATCH","出库批次不属于本PI及客户");
            if(batch.activeQty()<1)fail("LOGISTICS_BATCH_EMPTY","出库批次已全部撤销，不能登记发运");
            if(mapper.otherForBatch(batch.id(),logisticsId==null?0:logisticsId)>0)fail("LOGISTICS_BATCH_EXISTS","该真实批次已有物流，请修改或重新确认原记录");
        }
        if("SHIPPED".equals(request.status())) {
            if(batch==null)fail("LOGISTICS_ACTUAL_BATCH_REQUIRED","确认发运必须选择实际出库批次，手填批次不能作为出库依据");
            if(!"RELEASED".equals(pi.getReleaseStatus())||request.carrier()==null||request.trackingNo()==null)fail("LOGISTICS_NOT_READY","发运须已放行且填写承运商和运单号");
        }
        var normalized=new PiLogisticsRequest(batch==null?request.batchNo():batch.batchNo(),request.carrier(),request.trackingNo(),request.status(),request.remark(),request.stockOutBatchId(),request.requestId(),request.expectedRevision());
        Integer quantity="SHIPPED".equals(request.status())?batch.activeQty():null;
        long id;
        try {
            if(before==null){if(mapper.insert(piId,normalized,quantity,CurrentOperator.id())!=1)fail("PI_LOGISTICS_FAILED","物流保存失败");id=mapper.insertedId();}
            else {id=logisticsId;if(mapper.update(id,normalized,quantity)!=1)fail("LOGISTICS_REVISION_CONFLICT","物流版本已变化，请刷新");}
        } catch(org.springframework.dao.DuplicateKeyException error){throw new BusinessException("LOGISTICS_BATCH_EXISTS","该批次已有物流记录，请使用原记录");}
        finish(piId,id,request.requestId(),hash,before==null?"CREATE":"UPDATE",before,request.remark());return id;
    }
    @Transactional
    public long withdraw(long piId,long id,PiLogisticsWithdraw request) {
        requireWrite();validate(request);lock(piId);
        String hash=hash(Map.of("operation","WITHDRAW","id",id,"request",request));
        var prior=mapper.previous(piId,CurrentOperator.id(),request.requestId().trim());
        if(prior!=null){same(prior,hash);return prior.logisticsId();}
        var before=require(piId,id);
        if(mapper.withdraw(id,request.expectedRevision(),request.reason().trim())!=1)fail("LOGISTICS_REVISION_CONFLICT","物流已变更或已撤回，请刷新核对");
        finish(piId,id,request.requestId().trim(),hash,"WITHDRAW",before,request.reason().trim());return id;
    }
    public List<Map<String,Object>> history(long piId,long id) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_READ");
        require(piId,id);return mapper.history(id);
    }
    private PiLogisticsView require(long piId,long id) {
        var record=mapper.get(id);
        if(record==null||!Objects.equals(series.seriesId(piId),series.seriesId(record.getPiId())))fail("PI_LOGISTICS_NOT_FOUND","该PI没有此物流记录");
        return record;
    }
    private PiView lock(long piId) {
        guard.lock();for(long id:series.orderIds(piId))orders.selectForUpdate(id);
        var pi=pis.selectForUpdate(piId);if(pi==null)fail("PI_NOT_FOUND","PI不存在");return pi;
    }
    private void finish(long piId,long id,String key,String hash,String action,PiLogisticsView before,String remark) {
        var after=mapper.get(id);
        if(mapper.saveHistory(id,after.getRevision(),action,serialize(after),CurrentOperator.id())!=1
                ||mapper.saveRequest(piId,CurrentOperator.id(),key,hash,id)!=1
                ||logs.insert("PI",Long.toString(piId),"物流"+Map.of("CREATE","创建","UPDATE","更新","WITHDRAW","撤回").get(action),CurrentOperator.id(),CurrentOperator.username(),serialize(before),serialize(after),CurrentOperator.clientIp(),remark)!=1)
            fail("PI_LOGISTICS_FAILED","物流及历史审计保存失败");
    }
    private static void requireWrite(){CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_READ");CurrentOperator.requirePermission("PI_LOGISTICS_WRITE");}
    private void validate(Object request){if(request==null||!validator.validate(request).isEmpty())fail("INVALID_PARAMETER","物流参数不合法");}
    private void same(PiLogisticsMapper.Request prior,String hash){if(!prior.payloadHash().equals(hash))fail("LOGISTICS_REQUEST_CONFLICT","同一物流请求标识不能用于不同内容");}
    private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String serialize(Object value){try{return value==null?null:json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(String value){return value==null||value.isBlank()?null:value.trim();}
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
