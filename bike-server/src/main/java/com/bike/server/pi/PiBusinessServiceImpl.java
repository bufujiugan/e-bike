package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.order.*;
import com.bike.server.stock.SkuInventoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiBusinessServiceImpl implements PiBusinessService {
    @org.springframework.beans.factory.annotation.Autowired private PaymentPriorityService paymentPriority;
    @org.springframework.beans.factory.annotation.Autowired private PiOrderBindingService orderBinding;
    @org.springframework.beans.factory.annotation.Autowired private PiFulfillmentProfileService profiles;
    @org.springframework.beans.factory.annotation.Autowired private PiFulfillmentProfileMapper profileMapper;
    private final PiBusinessMapper mapper;
    private final PiMapper pis;
    private final PiSeriesMapper series;
    private final PiWorkflowMapper workflow;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final SkuInventoryMapper inventory;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    public PiBusinessServiceImpl(PiBusinessMapper mapper,PiMapper pis,PiSeriesMapper series,PiWorkflowMapper workflow,
            OrderMapper orders,OrderReservationMapper reservations,SkuInventoryMapper inventory,OperationLogMapper logs,ObjectMapper json) {
        this.mapper=mapper;this.pis=pis;this.series=series;this.workflow=workflow;this.orders=orders;
        this.reservations=reservations;this.inventory=inventory;this.logs=logs;this.json=json;
    }
    public List<Map<String,Object>> warehouses(){return mapper.selectWarehouses();}
    public PiDocumentSnapshot document(long piId) {
        String value=mapper.selectDocument(piId);
        try{return value==null?null:json.readValue(value,PiDocumentSnapshot.class);}
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("PI业务资料读取失败",error);}
    }
    public void validateTerms(PiDocumentSnapshot d,String tradeType) {
        if(d==null||d.business()==null||d.business().warehouseId()==null||d.business().domesticShipment()==null)
            fail("PI_BUSINESS_REQUIRED","请补齐出库仓库及是否国内出货；不同仓库须分别建立PI");
        if(mapper.countEnabledWarehouse(d.business().warehouseId())!=1)fail("WAREHOUSE_NOT_FOUND","出库仓库不存在或已停用");
        if(profiles!=null)profiles.validateSelection(d,tradeType,false);
        String expected="EXPORT".equals(tradeType)?"HONG_KONG":"GUANGTA";
        if(profile(d)==null&&!expected.equals(d.business().collectionEntity()))fail("PI_COLLECTION_ENTITY_INVALID","内销收款主体须为广塔，外销须为香港");
        if(blank(d.bankName())||blank(d.bankAccount())||blank(d.beneficiary()))fail("PI_BANK_REQUIRED","请确认收款主体对应的银行、账户和收款人");
    }
    private PiWorkflowDetails step(long piId,String code) {
        // 修订继承只按业务编码读取，避免旧流程顺序改变后串用其他步骤。
        var pi=pis.selectById(piId);
        for(int depth=0;pi!=null&&depth<100;depth++) {
            var row=workflow.selectSteps(pi.getId()).stream().filter(s->code.equals(s.code())&&"COMPLETED".equals(s.status())).findFirst().orElse(null);
            if(row!=null) {
                try{return row.detailJson()==null?null:json.readValue(row.detailJson(),PiWorkflowDetails.class);}
                catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("PI流程资料读取失败",error);}
            }
            if (!Set.of("PURCHASE_CONTRACT_READY", "FORWARDER_SELECTED").contains(code)) break;
            pi=pi.getPreviousRevisionId()==null?null:pis.selectById(pi.getPreviousRevisionId());
        }
        return null;
    }
    public void requireDocuments(long piId) {
        var d=step(piId,"DOCUMENTS_READY");
        var p=profile(document(piId));
        if(d==null||((p==null||p.rules().salesContractRequired())&&(blank(d.contractNo())||!Boolean.TRUE.equals(d.contractReady())))||!Boolean.TRUE.equals(d.paymentTableReady()))
            fail("PI_DOCUMENTS_REQUIRED","请先确认PI、销售合同及收付款表");
    }
    public void requireContract(long piId) {
        var pi=pis.selectById(piId);var d=document(piId);validateTerms(d,pi.getTradeType());requireDocuments(piId);
        var p=profile(d);
        if(p==null?Boolean.TRUE.equals(d.business().domesticShipment()):p.rules().purchaseContractRequired()) {
            var contract=step(piId,"PURCHASE_CONTRACT_READY");
            if(contract==null||blank(contract.purchaseContractNo())||(p==null?!Set.of("SAVA","YINSANHUAN").contains(Objects.toString(contract.purchaseContractParty(),"")):blank(contract.purchaseContractParty())))
                fail("PI_PURCHASE_CONTRACT_REQUIRED",p==null?"国内出货须补齐SAVA或银三环购销合同后才能出库":"请按履约配置补齐购销合同后再出库");
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean reserveAfterPayment(PiView pi,BigDecimal paid) {
        var d=document(pi.getId());
        boolean credit=creditApproved(pi.getId(),d);
        if(profile(d)!=null&&profile(d).rules().credit()&&!credit)return false;
        if(!credit&&(d==null||d.business()==null||pi.getDepositRequiredAmount()==null||paid==null||paid.signum()<=0||paid.compareTo(pi.getDepositRequiredAmount())<0))return false;
        validateTerms(d,pi.getTradeType());
        var lines=series.fulfillment(pi.getId());
        var items=pis.selectItems(pi.getId());
        if (items.isEmpty() || !lines.isEmpty() && !matchesFulfillment(items, lines)) return false;
        // 先校验所有SKU，库存不足只保留真实到账事实，不生成半套履约单。
        for(var item:items) {
            int shipped=lines.stream().filter(l->Objects.equals(l.skuId(),item.getSkuId())).mapToInt(PiFulfillmentLine::shippedQty).sum();
            int remaining=item.getQuantity()-shipped;
            if(mapper.countWarehouseStock(d.business().warehouseId(),item.getSkuId())-
                    mapper.countWarehouseReserved(d.business().warehouseId(),item.getSkuId(),pi.getSeriesId())<remaining)return false;
            var matching=lines.stream().filter(l->Objects.equals(l.skuId(),item.getSkuId())).toList();
            if(matching.isEmpty()&&(!lines.isEmpty()||pi.getSourceOrderId()!=null))return false;
            for(var line:matching) {
                if (line.remainingQty() == 0) continue;
                var reservation=reservations.selectForUpdate(line.orderId());
                if(reservation==null||"RELEASED".equals(reservation.holdMode()))return false;
            }
            int owned=matching.stream().filter(l->Set.of("PAID_HELD","CREDIT_HELD").contains(l.reservationStatus())).mapToInt(PiFulfillmentLine::remainingQty).sum();
            if(inventory.selectFulfillmentSnapshot(item.getSkuId(),0).availableForOrder()+owned<remaining)return false;
        }
        var modelNeeded=new HashMap<Long,Integer>();var modelOwned=new HashMap<Long,Long>();
        for(var item:items)modelNeeded.merge(item.getProductModelId(),item.getQuantity(),Integer::sum);
        for(var line:lines) {
            var order=orders.selectForUpdate(line.orderId());
            modelNeeded.merge(order.getModelId(),-line.shippedQty(),Integer::sum);
            long owned=reservations.selectFulfillmentSnapshot(order.getModelId(),line.orderId()).getOwnedQty();
            modelOwned.merge(order.getModelId(),owned,Long::sum);
        }
        for(var entry:modelNeeded.entrySet())if(reservations.selectFulfillmentSnapshot(entry.getKey(),0).availableForOrder()+modelOwned.getOrDefault(entry.getKey(),0L)<entry.getValue())return false;
        if(lines.isEmpty())for(var item:items)createReservedOrder(pi,item,credit);
        else for(var line:lines)if(line.remainingQty()>0&&!Set.of("PAID_HELD","CREDIT_HELD").contains(line.reservationStatus())) {
            if((credit?profileMapper.updateCreditReservation(line.orderId(),pi.getId(),CurrentOperator.id()):mapper.updatePaidReservation(line.orderId(),pi.getId(),CurrentOperator.id()))!=1)fail("PI_RESERVATION_FAILED","订单占用保留失败");
            if(Set.of("DRAFT","PENDING").contains(line.status())&&orders.updateStatus(line.orderId(),"CONFIRMED")!=1)
                fail("PI_RESERVATION_FAILED","已收款订单确认失败");
        }
        if(!credit)paymentPriority.cancelUnfulfillableUnpaidOrders(pi);
        if(logs.insert("PI",Long.toString(pi.getId()),"按仓确认收款库存占用",CurrentOperator.id(),CurrentOperator.username(),null,
                serialize(Map.of("warehouseId",d.business().warehouseId())),CurrentOperator.clientIp(),credit?"经授权保留后付款订单库存，未登记收款":"累计收款达到PI约定定金后保留指定仓库库存")!=1)
            fail("PI_AUDIT_FAILED","库存占用审计失败");
        return true;
    }
    private void createReservedOrder(PiView pi,PiItemView item,boolean credit) {
        String no="SO"+UUID.randomUUID().toString().replace("-","").substring(0,18).toUpperCase();
        var request=new SalesOrderCreateRequest(pi.getCustomerId(),pi.getAgentId(),item.getProductModelId(),item.getQuantity(),pi.getPlannedShipDate(),(credit?"PI后付款授权履约：":"PI收款后履约：")+pi.getPiNo());
        if(orders.insertOrder(no,request,CurrentOperator.id())!=1||orders.insertItem(no,request)!=1)fail("PI_RESERVATION_FAILED","履约订单创建失败");
        long orderId=orders.selectByNo(no).getId();String lockNo="LK"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
        if((credit?profileMapper.insertCreditSource(pi.getId(),item.getSkuId(),item.getQuantity(),lockNo,CurrentOperator.id()):mapper.insertPaidSource(pi.getId(),item.getSkuId(),item.getQuantity(),lockNo,CurrentOperator.id()))!=1)fail("PI_RESERVATION_FAILED","收款占用来源保存失败");
        Long lockId = mapper.selectLockId(lockNo);
        if (lockId == null) fail("PI_RESERVATION_FAILED", "收款占用来源读取失败");
        if((credit?profileMapper.insertCreditReservation(orderId,lockId,pi.getId(),CurrentOperator.id()):mapper.insertPaidReservation(orderId,lockId,pi.getId(),CurrentOperator.id()))!=1
                ||inventory.insertReservationItem(orderId,item.getSkuId(),item.getQuantity())!=1
                ||series.link(pi.getId(),orderId,credit?"CREDIT_PI":"PAID_PI")!=1||orders.updateStatus(orderId,"CONFIRMED")!=1)
            fail("PI_RESERVATION_FAILED","按仓库存占用创建失败");
    }
    public boolean hasReservedStock(PiView pi) {
        var lines=series.fulfillment(pi.getId());
        var document = document(pi.getId());
        return document != null && document.business() != null && document.business().warehouseId() != null
                && (creditApproved(pi.getId(),document) || pi.getPaidAmount() != null && pi.getPaidAmount().signum() > 0 && pi.getDepositRequiredAmount() != null
                && pi.getPaidAmount().compareTo(pi.getDepositRequiredAmount()) >= 0)
                && !lines.isEmpty() && matchesFulfillment(pis.selectItems(pi.getId()), lines)
                && lines.stream().allMatch(l -> l.remainingQty() == 0
                    || Set.of("PAID_HELD","CREDIT_HELD").contains(l.reservationStatus()) && Set.of("CONFIRMED", "PARTIAL_SHIPPED").contains(l.status()));
    }
    private boolean matchesFulfillment(List<PiItemView> items, List<PiFulfillmentLine> lines) {
        var expected = new HashMap<Long, Integer>();
        var actual = new HashMap<Long, Integer>();
        for (var item : items) expected.merge(item.getSkuId(), item.getQuantity(), Integer::sum);
        for (var line : lines) {
            int quantity = line.shippedQty() + line.remainingQty();
            if (quantity == 0) continue;
            if (line.skuId() == null || line.remainingQty() < 0) return false;
            actual.merge(line.skuId(), quantity, Integer::sum);
        }
        return !expected.isEmpty() && expected.equals(actual);
    }
    public void validateReservedWarehouse(PiView pi) {
        var d = document(pi.getId());
        validateTerms(d, pi.getTradeType());
        var held = new HashMap<Long, Integer>();
        for (var line : series.fulfillment(pi.getId())) {
            if (line.remainingQty() > 0 && Set.of("PAID_HELD","CREDIT_HELD").contains(line.reservationStatus())) {
                if (line.skuId() == null) fail("PI_LEGACY_SKU_REQUIRED", "历史订单缺少精确SKU，请先核对履约关系");
                held.merge(line.skuId(), line.remainingQty(), Integer::sum);
            }
        }
        for (var entry : held.entrySet()) {
            if (mapper.countWarehouseStock(d.business().warehouseId(), entry.getKey())
                    - mapper.countWarehouseReserved(d.business().warehouseId(), entry.getKey(), pi.getSeriesId()) < entry.getValue()) {
                fail("PI_WAREHOUSE_STOCK_SHORTAGE", "修订后的已收款占用超过指定仓库库存");
            }
        }
    }
    public void requireShipment(long piId) {
        requireContract(piId);var pi=pis.selectById(piId);
        if (orderBinding != null) orderBinding.requireConsistentFulfillment(pi);
        requirePaymentAuthorization(piId);
        if (mapper.countPaymentsWithoutReceipt(piId) > 0)
            fail("PI_RECEIPT_REQUIRED", "请为已确认收款上传收款凭证后再放行出库");
        var profile=profile(document(piId));
        if(profile!=null) {
            if(profile.rules().dispatchSheetRequired()&&step(piId,"DISPATCH_SHEET_SENT")==null)fail("PI_PREPARATION_REQUIRED","请先同步出货表");
            if(profile.rules().preparationRequired()&&step(piId,"GOODS_PREPARED")==null)fail("PI_PREPARATION_REQUIRED","请先确认备货完成");
            if(step(piId,"FORWARDER_SELECTED")==null)fail("PI_DELIVERY_REQUIRED","请先确认提货或运输安排");
        }
        if(!hasReservedStock(pi))fail("PI_WAREHOUSE_RESERVATION_REQUIRED","请先确认指定仓库库存占用");
    }
    public void requirePaymentAuthorization(long piId) {
        var document=document(piId);var configured=profile(document);
        if(configured!=null&&configured.rules().credit()&&!creditApproved(piId,document))
            fail("PI_CREDIT_APPROVAL_REQUIRED","后付款订单须先完成本修订的授权");
    }
    public void requireVehicle(long piId,long vehicleId) {
        var d=document(piId);
        if(d==null||d.business()==null||!Objects.equals(d.business().warehouseId(),mapper.selectVehicleWarehouse(vehicleId)))
            fail("PI_WAREHOUSE_MISMATCH","整车不属于本PI指定的出库仓库");
    }
    public void requireWarehouseCapacity(long vehicleId,Long orderId) {
        Long warehouse=mapper.selectVehicleWarehouse(vehicleId),sku=mapper.selectVehicleSku(vehicleId);
        if(warehouse==null||sku==null)return;
        Long piId=orderId==null?null:series.currentPiForOrder(orderId);
        long excluded=piId==null?0:Objects.requireNonNullElse(series.seriesId(piId),0L);
        if(mapper.countWarehouseStock(warehouse,sku)-mapper.countWarehouseReserved(warehouse,sku,excluded)<1)
            fail("WAREHOUSE_STOCK_RESERVED","该仓库SKU库存已被其他PI占用，不能出库或撤销入库");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void invalidateDelivery(long piId) {
        mapper.updateDeliveryPending(piId);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void invalidateDeliveryForOrder(long orderId) {
        Long piId = series.currentPiForOrder(orderId);
        if (piId != null) mapper.updateDeliveryPending(piId);
    }
    public boolean allDispatched(long piId){return mapper.countActiveShipments(piId)>0&&mapper.countUnshipped(piId)==0;}
    public boolean isCustomerForwarder(long piId){var d=step(piId,"FORWARDER_SELECTED");var p=profile(document(piId));return p!=null?p.rules().customerPickup():d!=null&&"CUSTOMER".equals(d.forwarderResponsibility());}
    public boolean allTracked(long piId){return allDispatched(piId)&&mapper.countUntrackedBatches(piId)==0;}
    public void requireArrivalProof(long piId,String id){if(blank(id)||mapper.countArrivalProof(piId,id)<1)fail("PI_ARRIVAL_PROOF_REQUIRED","请先上传本PI物流附件，并选择到货凭证");}
    private PiFulfillmentProfile profile(PiDocumentSnapshot document){return profiles==null?null:profiles.resolveProfile(document);}
    private boolean creditApproved(long piId,PiDocumentSnapshot document){var p=profile(document);return p!=null&&p.rules().credit()&&step(piId,"CREDIT_APPROVED")!=null;}
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("库存审计序列化失败",error);}}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
