package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.common.PageResponse;
import com.bike.server.order.OrderReservationCoordinator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiServiceImpl implements PiService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");
    private static final Set<String> PUBLISHED = Set.of("CONFIRMED", "RELEASED");
    private final PiMapper mapper;
    private final PiDocumentService documents;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final Validator validator;
    private final OrderReservationCoordinator reservations;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.stock.SkuInventoryMapper skuInventory;
    @org.springframework.beans.factory.annotation.Autowired private PiSeriesMapper series;
    @org.springframework.beans.factory.annotation.Autowired private PiDispatchMapper dispatch;
    @org.springframework.beans.factory.annotation.Autowired private PiLogisticsService logisticsService;
    @org.springframework.beans.factory.annotation.Autowired private PiLogisticsMapper logisticsMapper;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.order.OrderMapper fulfillmentOrders;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.order.OrderInventoryGuard orderInventoryGuard;

    public PiServiceImpl(PiMapper mapper, PiDocumentService documents, OperationLogMapper logs,
            ObjectMapper json, Validator validator, OrderReservationCoordinator reservations) {
        this.mapper = mapper;
        this.documents = documents;
        this.logs = logs;
        this.json = json;
        this.validator = validator;
        this.reservations = reservations;
    }

    @Override
    public PiOptionsView options(String keyword) {
        requireInternal("PI_WRITE");
        String query = normalize(keyword);
        var orderOptions=mapper.selectOrderOptions(query);
        if(series!=null) orderOptions=orderOptions.stream().filter(order->series.currentPiForOrder(((Number)order.get("id")).longValue())==null).toList();
        return new PiOptionsView(mapper.selectCustomerOptions(query), mapper.selectAgentOptions(query),
                mapper.selectSkuOptions(query), orderOptions);
    }

    @Override
    public PageResponse<PiView> list(String keyword, String status, long page, long pageSize) {
        requireRead();
        long p = Math.min(10_000_000, Math.max(1, page));
        long size = Math.min(100, Math.max(1, pageSize));
        Long agent = CurrentOperator.hasRole("WEB_AGENT") ? agentId() : null;
        String query = normalize(keyword), state = upper(status);
        List<PiView> records = mapper.selectPage(query, state, agent, (p - 1) * size, size);
        records.forEach(pi -> { enrichFulfillment(pi); redactFinance(pi); });
        return new PageResponse<>(records,
                p, size, mapper.count(query, state, agent));
    }

    @Override
    public PiView get(long id) {
        requireRead();
        PiView pi = require(id);
        checkReadScope(pi);
        return enrich(pi);
    }

    @Override
    @Transactional
    public PiView create(PiCreateRequest request) {
        requireInternal("PI_WRITE");
        validate(request);
        reservations.lockInventory();
        return persistDraft(null, request);
    }

    @Override
    @Transactional
    public PiView update(long id, PiCreateRequest request) {
        requireInternal("PI_WRITE");
        validate(request);
        PiView existing = lockOrderAndPi(id);
        if (!"DRAFT".equals(existing.getStatus())) fail("INVALID_STATUS_TRANSITION", "只有草稿 PI 可以编辑");
        if (!Objects.equals(existing.getSourceOrderId(), request.sourceOrderId())) {
            fail("PI_SOURCE_ORDER_IMMUTABLE", "草稿的来源订单不可改绑，请取消后重新核对业务");
        }
        requireNoFinancialOrShipmentActivity(existing);
        checkLinkedDraft(existing, request);
        return persistDraft(existing, request);
    }

    @Override
    @Transactional
    public PiView cancel(long id, PiCancelRequest request) {
        requireInternal("PI_WRITE");
        validate(request);
        PiView existing = lockOrderAndPi(id);
        if (!Set.of("DRAFT", "CONFIRMED").contains(existing.getStatus())) {
            fail("INVALID_STATUS_TRANSITION", "只有未放行的草稿或已确认 PI 可以取消");
        }
        requireNoFinancialOrShipmentActivity(existing);
        for (long orderId : orderIds(existing)) reservations.release(orderId, "取消PI：" + request.reason().trim());
        if (mapper.updateStatus(id, existing.getStatus(), "CANCELLED", CurrentOperator.id(), false) != 1) {
            fail("PI_CANCEL_FAILED", "PI 取消失败");
        }
        audit(id, "取消 PI", Map.of("status", existing.getStatus()),
                Map.of("status", "CANCELLED", "releaseStatus", "BLOCKED"), request.reason().trim());
        return enrich(require(id));
    }

    private PiView persistDraft(PiView existing, PiCreateRequest request) {
        if (mapper.countEnabledCustomer(request.customerId()) != 1) {
            fail("CUSTOMER_NOT_FOUND", "客户不存在或已停用");
        }
        if (request.agentId() != null && mapper.countEnabledAgent(request.agentId()) != 1) {
            fail("AGENT_NOT_FOUND", "代理商不存在或已停用");
        }
        String trade = upper(request.tradeType());
        if (!Set.of("DOMESTIC", "EXPORT").contains(trade)) {
            fail("TRADE_TYPE_INVALID", "贸易类型必须为 DOMESTIC 或 EXPORT");
        }
        String currency = currency(request.currencyCode());
        List<PiCalculatedItem> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        BigDecimal subtotal = ZERO, lineDiscount = ZERO, lineTax = ZERO;
        for (PiItemRequest item : request.items()) {
            if (!seen.add(item.skuId())) fail("DUPLICATE_SKU", "PI 明细不能重复 SKU");
            PiSkuSnapshot sku = mapper.selectEnabledSku(item.skuId());
            if (sku == null) fail("SKU_NOT_FOUND", "SKU 或所属车型不存在或已停用");
            BigDecimal price = money(item.unitPrice());
            BigDecimal discount = optionalMoney(item.discountAmount());
            BigDecimal tax = optionalMoney(item.taxAmount());
            BigDecimal raw = money(price.multiply(BigDecimal.valueOf(item.quantity())));
            if (discount.compareTo(raw) > 0) fail("AMOUNT_INVALID", "行折扣不能大于行金额");
            BigDecimal line = money(raw.subtract(discount).add(tax));
            items.add(new PiCalculatedItem(sku.id(), sku.productModelId(), sku.skuCode(),
                    sku.productName(), sku.specification(), sku.modelName(), item.quantity(),
                    price, discount, tax, line));
            subtotal = money(subtotal.add(raw));
            lineDiscount = money(lineDiscount.add(discount));
            lineTax = money(lineTax.add(tax));
        }
        checkSourceOrder(request, items, existing != null);
        BigDecimal discount = money(optionalMoney(request.discountAmount()).add(lineDiscount));
        BigDecimal tax = money(optionalMoney(request.taxAmount()).add(lineTax));
        BigDecimal freight = optionalMoney(request.freightAmount());
        if (discount.compareTo(subtotal) > 0) fail("AMOUNT_INVALID", "总折扣不能大于商品金额");
        BigDecimal total = money(subtotal.subtract(discount).add(tax).add(freight));
        BigDecimal deposit = money(request.depositRequiredAmount());
        BigDecimal shipment = money(request.shipmentRequiredAmount());
        if (deposit.compareTo(total) > 0 || shipment.compareTo(total) > 0) {
            fail("AMOUNT_INVALID", "保留库存及放行发货所需收款金额不能大于应付总额");
        }
        long id;
        if (existing == null) {
            String no = "PI" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            if (mapper.insertPi(no, request, trade, currency, subtotal, discount, tax, freight,
                    total, deposit, shipment, serialize(request.document()), request.agentId(),
                    CurrentOperator.id()) != 1) fail("PI_CREATE_FAILED", "PI 创建失败");
            id = mapper.selectInsertedId();
        } else {
            id = existing.getId();
            if (mapper.updatePi(id, request, trade, currency, subtotal, discount, tax, freight,
                    total, deposit, shipment, serialize(request.document()), request.agentId()) != 1) {
                fail("PI_UPDATE_FAILED", "PI 草稿更新失败");
            }
            mapper.deleteItems(id);
        }
        if (mapper.insertItems(id, items) != items.size()) fail("PI_CREATE_FAILED", "PI 明细创建失败");
        if (existing == null && series != null) {
            if (series.insertSeries(id) != 1 || series.assignSeries(id) != 1) fail("PI_CREATE_FAILED", "PI系列创建失败");
            if (request.sourceOrderId() != null && series.link(id,request.sourceOrderId(),"LEGACY_SOURCE") != 1)
                fail("PI_CREATE_FAILED", "PI来源订单关联失败");
        }
        audit(id, existing == null ? "创建 PI 草稿" : "编辑 PI 草稿", existing,
                Map.of("status", "DRAFT", "totalAmount", total, "currency", currency,
                        "skuCount", items.size(), "depositRequiredAmount", deposit,
                        "shipmentRequiredAmount", shipment, "request", request), request.remark());
        return enrich(require(id));
    }

    @Override
    @Transactional
    public PiView confirm(long id) {
        requireInternal("PI_WRITE");
        PiView pi = lockOrderAndPi(id);
        requirePublishableDocument(pi);
        if (series != null && pi.getSourceOrderId() == null) {
            for (long orderId : orderIds(pi)) {
                var order = fulfillmentOrders.selectForUpdate(orderId);
                if (!Set.of("DRAFT","PENDING","CONFIRMED").contains(order.getStatus()))
                    fail("INVALID_STATUS_TRANSITION", "履约子单已关闭或已有出库，不能确认PI");
                orderInventoryGuard.requireConfirmable(orderId);
                if (!"CONFIRMED".equals(order.getStatus())) {
                    if(fulfillmentOrders.updateStatus(orderId,"CONFIRMED")!=1
                            ||fulfillmentOrders.insertOperationLog("ORDER_UPDATE",order.getOrderNo(),"随PI确认履约订单",
                            CurrentOperator.id(),CurrentOperator.username(),order.getStatus(),"CONFIRMED",CurrentOperator.clientIp(),"PI:"+id)!=1)
                        fail("PI_CONFIRM_FAILED","履约订单确认失败");
                }
            }
        }
        if (!"DRAFT".equals(pi.getStatus())
                || mapper.updateStatus(id, "DRAFT", "CONFIRMED", CurrentOperator.id(), false) != 1) {
            fail("INVALID_STATUS_TRANSITION", "只有草稿 PI 可以确认");
        }
        if (pi.getPreviousRevisionId() != null && pi.getSeriesId() != null) {
            if (series.publishRevision(pi.getSeriesId(), id, pi.getPreviousRevisionId(), pi.getRevision()) != 1)
                fail("PI_REVISION_CONFLICT", "PI当前版本已变化，请刷新后重试");
            mapper.updateStatus(pi.getPreviousRevisionId(), "CONFIRMED", "SUPERSEDED", CurrentOperator.id(), false);
            BigDecimal seriesPaid = mapper.paymentTotal(id);
            mapper.updatePaymentStatus(id, paymentStatus(seriesPaid, pi));
        }
        audit(id, "确认 PI", Map.of("status", "DRAFT"), Map.of("status", "CONFIRMED"), null);
        if (pi.getDepositRequiredAmount().signum() == 0) reservations.retainAfterPayment(pi, mapper.paymentTotal(id));
        return enrich(require(id));
    }

    @Override
    @Transactional
    public PiView recordPayment(long id, PiPaymentRequest request) {
        requireInternal("PI_PAYMENT_CONFIRM");
        validate(request);
        PiPaymentRequest normalized = new PiPaymentRequest(normalize(request.requestId()),
                money(request.amount()), currency(request.currencyCode()),
                normalize(request.voucherObjectKey()), request.receivedAt() == null ? null
                        : request.receivedAt().truncatedTo(ChronoUnit.MILLIS), normalize(request.remark()));
        if (normalized.receivedAt() != null && normalized.receivedAt().isAfter(LocalDateTime.now())) {
            fail("PAYMENT_DATE_INVALID", "到账时间不能晚于当前时间");
        }
        PiView pi = lockOrderAndPi(id);
        if (!Objects.equals(normalized.currencyCode(), pi.getCurrencyCode())) {
            fail("CURRENCY_MISMATCH", "收款币种与 PI 不一致");
        }
        PiPaymentView previous = mapper.selectPaymentByRequest(id, normalized.requestId());
        if (previous != null) {
            if (!samePayment(previous, normalized)) fail("PAYMENT_REQUEST_CONFLICT", "同一收款请求标识不能用于不同收款内容");
            return enrich(pi);
        }
        if (!PUBLISHED.contains(pi.getStatus())) fail("INVALID_STATUS_TRANSITION", "只有已确认 PI 可以登记收款");
        BigDecimal before = mapper.paymentTotal(id);
        BigDecimal after = money(before.add(normalized.amount()));
        if (mapper.insertPayment(id, normalized, CurrentOperator.id()) != 1) fail("PI_PAYMENT_FAILED", "收款登记失败");
        String paymentStatus = paymentStatus(after, pi);
        if (mapper.updatePaymentStatus(id, paymentStatus) != 1) fail("PI_PAYMENT_FAILED", "收款状态更新失败");
        String reservationStatus = reservations.retainAfterPayment(pi, after);
        audit(id, "登记 PI 收款", Map.of("paidAmount", before, "paymentStatus", pi.getPaymentStatus()),
                Map.of("paidAmount", after, "paymentStatus", paymentStatus, "requestId", normalized.requestId(),
                        "amount", normalized.amount(), "currency", normalized.currencyCode(),
                        "reservationStatus", Objects.toString(reservationStatus, "NONE")), normalized.remark());
        return enrich(require(id));
    }

    @Override
    @Transactional
    public PiView release(long id, PiReleaseRequest request) {
        requireInternal("PI_RELEASE");
        validate(request);
        PiView pi = lockOrderAndPi(id);
        if (!"CONFIRMED".equals(pi.getStatus())) fail("INVALID_STATUS_TRANSITION", "只有已确认且未放行的 PI 可以放行");
        BigDecimal required = pi.getShipmentRequiredAmount();
        BigDecimal paid = mapper.paymentTotal(id);
        if (required == null || paid.compareTo(required) < 0) {
            fail("PAYMENT_GATE_NOT_MET", "未达到该 PI 约定的发货收款条件，不能放行发货");
        }
        for (long orderId : orderIds(pi)) {
            var reservation = reservations.lockReservation(orderId);
            if (reservation != null && java.util.Set.of("EXPIRED_REQUIRES_REACQUIRE", "RELEASED")
                    .contains(reservation.reservationStatus())) fail("ORDER_RESERVATION_EXPIRED", "订单占用已失效，须重新核对库存并办理占用后放行");
        }
        if (mapper.updateStatus(id, "CONFIRMED", "RELEASED", CurrentOperator.id(), true) != 1) {
            fail("PI_RELEASE_FAILED", "PI 发货放行失败");
        }
        audit(id, "放行 PI 发货", Map.of("status", "CONFIRMED", "releaseStatus", "BLOCKED"),
                Map.of("status", "RELEASED", "releaseStatus", "RELEASED", "paidAmount", paid,
                        "shipmentRequiredAmount", required), request.reason().trim());
        return enrich(require(id));
    }

    @Override
    @Transactional
    public PiView addLogistics(long id, PiLogisticsRequest request) {
        if(logisticsService!=null) {logisticsService.save(id,null,request);return get(id);}
        requireInternal("PI_LOGISTICS_WRITE");
        validate(request);
        String state = upper(request.status());
        if (state == null) state = "PLANNED";
        if (!Set.of("PLANNED", "SHIPPED").contains(state)) fail("LOGISTICS_STATUS_INVALID", "物流状态必须为 PLANNED 或 SHIPPED");
        PiView pi = lockOrderAndPi(id);
        if (!PUBLISHED.contains(pi.getStatus())) fail("INVALID_STATUS_TRANSITION", "当前 PI 不可登记物流");
        if ("SHIPPED".equals(state) && (!"RELEASED".equals(pi.getReleaseStatus())
                || normalize(request.carrier()) == null || normalize(request.trackingNo()) == null)) {
            fail("LOGISTICS_NOT_READY", "登记已发货物流须先放行 PI，并填写承运商及运单号");
        }
        PiLogisticsRequest normalized = new PiLogisticsRequest(request.batchNo().trim(),
                normalize(request.carrier()), normalize(request.trackingNo()), state, normalize(request.remark()));
        try {
            if (mapper.insertLogistics(id, normalized, CurrentOperator.id()) != 1) fail("PI_LOGISTICS_FAILED", "物流登记失败");
        } catch (org.springframework.dao.DuplicateKeyException error) {
            fail("LOGISTICS_BATCH_EXISTS", "该 PI 已有相同物流批次，请使用原记录");
        }
        audit(id, "登记 PI 物流", null, Map.of("batchNo", normalized.batchNo(), "status", state,
                "carrier", Objects.toString(normalized.carrier(), ""),
                "trackingNo", Objects.toString(normalized.trackingNo(), "")), normalized.remark());
        return enrich(require(id));
    }

    @Override
    public byte[] excel(long id) {
        CurrentOperator.requirePermission("PI_EXPORT");
        return documents.excel(get(id));
    }

    @Override
    public byte[] pdf(long id) {
        CurrentOperator.requirePermission("PI_EXPORT");
        return documents.pdf(get(id));
    }

    private void checkSourceOrder(PiCreateRequest request, List<PiCalculatedItem> items, boolean editing) {
        if (request.sourceOrderId() == null) return;
        if (mapper.lockOrder(request.sourceOrderId()) == null) fail("ORDER_NOT_FOUND", "来源订单不存在");
        reservations.lockReservation(request.sourceOrderId());
        if (!editing && (mapper.countForOrder(request.sourceOrderId()) > 0
                || series != null && series.currentPiForOrder(request.sourceOrderId()) != null)) fail("ORDER_PI_EXISTS", "来源订单已关联 PI");
        if (mapper.countOrder(request.sourceOrderId(), request.customerId(), request.agentId()) != 1) {
            fail("ORDER_NOT_FOUND", "来源订单不存在、已关闭、已有发货，或与 PI 客户/代理商不一致");
        }
        Map<Long, Long> requested = new TreeMap<>();
        for (PiCalculatedItem item : items) requested.merge(item.productModelId(), (long) item.quantity(), Long::sum);
        Map<Long, Long> ordered = new TreeMap<>();
        for (PiOrderLine line : mapper.selectOrderLines(request.sourceOrderId())) {
            if (line.shippedQuantity() > 0) fail("ORDER_ALREADY_SHIPPED", "来源订单已有发货，不能补建 PI 门槛");
            ordered.merge(line.productModelId(), (long) line.quantity(), Long::sum);
        }
        if (ordered.size() != 1) fail("SOURCE_ORDER_UNSUPPORTED", "当前来源订单履约仅支持单车型，请先完善订单明细后关联");
        if (!requested.equals(ordered)) fail("ORDER_ITEMS_MISMATCH", "PI 的 SKU 所属车型及合计数量须与来源订单一致");
        if(skuInventory!=null) {
            var reserved=skuInventory.reservationItems(request.sourceOrderId());
            if(!reserved.isEmpty()) {
                Map<Long,Long> fixedSkus=new TreeMap<>(),requestedSkus=new TreeMap<>();
                for(var item:reserved)fixedSkus.merge(item.skuId(),(long)item.orderedQty()-item.closedQty(),Long::sum);
                for(var item:items)requestedSkus.merge(item.skuId(),(long)item.quantity(),Long::sum);
                if(!fixedSkus.equals(requestedSkus))fail("ORDER_SKU_MISMATCH","PI的SKU及数量必须与来源订单固定SKU完全一致");
            }
        }
    }

    private PiView enrich(PiView pi) {
        enrichFulfillment(pi);
        if(dispatch!=null)pi.setDispatchBatches(dispatch.batches(pi.getId()));
        pi.setItems(mapper.selectItems(pi.getId()));
        pi.setLogistics(logisticsMapper==null?mapper.selectLogistics(pi.getId()):logisticsMapper.list(pi.getId()));
        if (canReadFinance()) pi.setPayments(mapper.selectPayments(pi.getId()));
        if (pi.getDocumentJson() != null) {
            try { pi.setDocument(json.readValue(pi.getDocumentJson(), PiDocumentSnapshot.class)); }
            catch (JsonProcessingException error) { throw new BusinessException("PI_DOCUMENT_INVALID", "PI 单据快照无法读取"); }
        }
        redactFinance(pi);
        return pi;
    }

    private static boolean canReadFinance() {
        return !CurrentOperator.hasRole("WEB_AGENT") && (CurrentOperator.hasRole("SUPER_ADMIN")
                || CurrentOperator.hasPermission("PI_PAYMENT_CONFIRM") || CurrentOperator.hasPermission("PI_RELEASE"));
    }

    private void redactFinance(PiView pi) {
        if (!canReadFinance()) {
            pi.setPayments(List.of());
            pi.setPaidAmount(null);
            pi.setPaymentStatus(null);
        }
        if (CurrentOperator.hasRole("WEB_AGENT")) {
            pi.setDepositRequiredAmount(null);
            pi.setShipmentRequiredAmount(null);
            pi.setReleaseStatus(null);
            pi.setReleasedAt(null);
            if (Set.of("PAID_HELD", "TIMED_ACTIVE").contains(Objects.toString(pi.getReservationStatus(), ""))) {
                pi.setReservationStatus("ACTIVE");
            }
        }
    }

    private void requirePublishableDocument(PiView pi) {
        if (!"DRAFT".equals(pi.getStatus())) fail("INVALID_STATUS_TRANSITION", "只有草稿 PI 可以确认");
        PiDocumentSnapshot snapshot;
        try {
            snapshot = pi.getDocumentJson() == null ? null
                    : json.readValue(pi.getDocumentJson(), PiDocumentSnapshot.class);
        } catch (JsonProcessingException error) {
            throw new BusinessException("PI_DOCUMENT_INVALID", "PI 单据快照无法读取");
        }
        if (snapshot == null || normalize(snapshot.sellerName()) == null
                || normalize(snapshot.paymentTerms()) == null || normalize(snapshot.deliveryTerms()) == null
                || normalize(snapshot.templateCode()) == null || normalize(snapshot.templateVersion()) == null) {
            fail("PI_DOCUMENT_INCOMPLETE", "确认 PI 前须填写卖方、付款与交付条款以及模板编号和版本");
        }
    }

    private PiView require(long id) {
        PiView pi = mapper.selectById(id);
        if (pi == null) fail("PI_NOT_FOUND", "PI 不存在");
        return pi;
    }

    private PiView locked(long id) {
        if (mapper.lockPi(id) == null) fail("PI_NOT_FOUND", "PI 不存在");
        PiView pi = mapper.selectForUpdate(id);
        if (pi == null) fail("PI_NOT_FOUND", "PI 不存在");
        return pi;
    }

    private PiView lockOrderAndPi(long id) {
        reservations.lockInventory();
        PiView observed = require(id);
        for (long orderId : orderIds(observed)) {
            if(mapper.lockOrder(orderId)==null) fail("ORDER_NOT_FOUND", "来源订单不存在");
            reservations.lockReservation(orderId);
        }
        return locked(id);
    }

    private void requireNoFinancialOrShipmentActivity(PiView pi) {
        if (mapper.countPayments(pi.getId()) > 0) fail("PI_HAS_PAYMENTS", "PI 已有收款记录，不能编辑或直接取消");
        for (long orderId : orderIds(pi)) {
            if(mapper.countShipments(orderId)>0) fail("PI_HAS_SHIPMENTS", "PI 来源订单已有实际出库记录，不能编辑或直接取消");
        }
    }

    private List<Long> orderIds(PiView pi) {
        if(series!=null) return series.orderIds(pi.getId());
        return pi.getSourceOrderId()==null ? List.of() : List.of(pi.getSourceOrderId());
    }

    private void enrichFulfillment(PiView pi) {
        if(series==null) return;
        pi.setSeriesId(series.seriesId(pi.getId()));
        var lines=series.fulfillment(pi.getId());
        if(CurrentOperator.hasRole("WEB_AGENT")) lines=lines.stream().map(line -> new PiFulfillmentLine(
                line.orderId(),line.orderNo(),line.status(),line.skuId(),line.skuCode(),line.orderedQty(),
                line.shippedQty(),line.closedQty(),line.remainingQty(),Set.of("PAID_HELD","TIMED_ACTIVE").contains(line.reservationStatus())?"ACTIVE":line.reservationStatus())).toList();
        pi.setFulfillment(lines);
        if(!lines.isEmpty()) {
            var states=lines.stream().map(PiFulfillmentLine::reservationStatus).distinct().toList();
            pi.setReservationStatus(states.size()==1?states.getFirst():"MIXED");
        }
    }

    private void checkLinkedDraft(PiView pi,PiCreateRequest request) {
        if(series==null||pi.getSourceOrderId()!=null) return;
        var lines=series.fulfillment(pi.getId());
        if(lines.isEmpty()) return;
        if(!Objects.equals(pi.getCustomerId(),request.customerId())||!Objects.equals(pi.getAgentId(),request.agentId()))
            fail("LOCK_CUSTOMER_MISMATCH","锁定转换的PI必须保留原客户和代理商");
        var fixed=new TreeMap<Long,Long>();
        for(var line:lines) {
            if(line.skuId()==null) fail("ORDER_SKU_MISMATCH","履约子单缺少SKU，请先核对");
            fixed.merge(line.skuId(),(long)line.orderedQty()-line.closedQty(),Long::sum);
        }
        var actual=new TreeMap<Long,Long>();
        for(var item:request.items())actual.merge(item.skuId(),(long)item.quantity(),Long::sum);
        if(!fixed.equals(actual))fail("ORDER_SKU_MISMATCH","草稿SKU及数量必须与所有履约子单一致");
    }

    private static void requireRead() {
        CurrentOperator.requireWebAccess();
        CurrentOperator.requirePermission("PI_READ");
    }

    private static void requireInternal(String permission) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission(permission);
    }

    private static void checkReadScope(PiView pi) {
        if (CurrentOperator.hasRole("WEB_AGENT") && (!Objects.equals(agentId(), pi.getAgentId())
                || !PUBLISHED.contains(pi.getStatus()))) fail("FORBIDDEN", "无权访问该 PI 或 PI 尚未发布");
    }

    private static Long agentId() {
        Long id = CurrentOperator.agentId();
        if (id == null) fail("AGENT_NOT_BOUND", "当前账号未绑定代理商");
        return id;
    }

    private <T> void validate(T request) {
        if (request == null) fail("INVALID_PARAMETER", "请求内容不能为空");
        var errors = validator.validate(request);
        if (!errors.isEmpty()) fail("INVALID_PARAMETER", errors.stream()
                .map(jakarta.validation.ConstraintViolation::getMessage).sorted().findFirst().orElse("参数不正确"));
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new BusinessException("PI_DOCUMENT_INVALID", "PI 单据快照无法保存"); }
    }

    private void audit(long id, String action, Object before, Object after, String remark) {
        String summary = normalize(remark);
        if (summary != null && summary.length() > 500) summary = summary.substring(0, 500);
        if (logs.insert("PI", Long.toString(id), action, CurrentOperator.id(), CurrentOperator.username(),
                serialize(before), serialize(after), CurrentOperator.clientIp(), summary) != 1) {
            fail("PI_AUDIT_FAILED", "PI 操作记录保存失败");
        }
    }

    private static boolean samePayment(PiPaymentView previous, PiPaymentRequest request) {
        return previous.amount().compareTo(request.amount()) == 0
                && Objects.equals(previous.currencyCode(), request.currencyCode())
                && Objects.equals(previous.voucherObjectKey(), request.voucherObjectKey())
                && Objects.equals(previous.remark(), request.remark())
                && (request.receivedAt() == null || Objects.equals(previous.receivedAt(), request.receivedAt()));
    }

    private static String paymentStatus(BigDecimal paid, PiView pi) {
        if (paid.compareTo(pi.getTotalAmount()) >= 0) return "PAID";
        if (paid.compareTo(pi.getDepositRequiredAmount()) >= 0) return "DEPOSIT_MET";
        return paid.signum() > 0 ? "PARTIAL" : "UNPAID";
    }

    private static String currency(String value) {
        String normalized = upper(value);
        try { Currency.getInstance(normalized); }
        catch (IllegalArgumentException | NullPointerException error) { throw new BusinessException("CURRENCY_INVALID", "请使用有效的三位 ISO 币种代码"); }
        return normalized;
    }

    private static BigDecimal optionalMoney(BigDecimal value) { return value == null ? ZERO : money(value); }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MONEY) > 0) {
            fail("AMOUNT_INVALID", "金额须为非负数且不能超出16位整数和2位小数的范围");
        }
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException error) { throw new BusinessException("AMOUNT_INVALID", "金额最多保留两位小数，请先确认金额计算口径"); }
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String upper(String value) { String text = normalize(value); return text == null ? null : text.toUpperCase(Locale.ROOT); }
    private static void fail(String code, String message) { throw new BusinessException(code, message); }
}
