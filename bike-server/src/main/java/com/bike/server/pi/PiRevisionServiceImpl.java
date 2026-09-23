package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiRevisionServiceImpl implements PiRevisionService {
    private final PiMapper mapper;
    private final PiSeriesMapper series;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final PiService service;
    private final com.bike.server.order.OrderReservationCoordinator reservations;

    public PiRevisionServiceImpl(PiMapper mapper, PiSeriesMapper series, OperationLogMapper logs,
            ObjectMapper json, PiService service, com.bike.server.order.OrderReservationCoordinator reservations) {
        this.mapper = mapper; this.series = series; this.logs = logs; this.json = json; this.service = service;
        this.reservations = reservations;
    }

    @Override @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PiView create(long piId, PiRevisionRequest request) {
        require("PI_WRITE");
        if (request == null || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 64
                || request.expectedRevision() < 1 || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500)
            fail("INVALID_PARAMETER", "请填写有效的修订请求、版本及原因");
        reservations.lockInventory();
        service.get(piId);
        PiView base = mapper.selectForUpdate(piId);
        if (base == null || base.getSeriesId() == null) fail("PI_NOT_FOUND", "PI不存在或尚未建立系列");
        String hash = sha256(base.getSeriesId()+"|"+request.expectedRevision()+"|"+request.reason().trim());
        var current = series.lockSeries(base.getSeriesId());
        PiSeriesMapper.RevisionRequest previous = series.revisionRequest(CurrentOperator.id(), request.requestId());
        if (previous != null) {
            if (!hash.equals(previous.payloadHash())) fail("PI_REVISION_REQUEST_CONFLICT", "同一请求标识不能用于不同修订内容");
            return service.get(previous.piId());
        }
        if (base.getRevision() != request.expectedRevision()) fail("PI_REVISION_CONFLICT", "PI版本已变化，请刷新后重试");
        if (!Set.of("CONFIRMED", "RELEASED").contains(base.getStatus())) fail("PI_REVISION_STATUS", "只有已确认或已放行PI可以创建修订");
        if (current == null || current.currentPiId() != piId) fail("PI_REVISION_CONFLICT", "该PI已不是当前有效版本");
        var history = mapper.selectRevisions(base.getSeriesId());
        if (history.stream().anyMatch(v -> "DRAFT".equals(v.getStatus())))
            fail("PI_REVISION_DRAFT_EXISTS", "该系列已有修订草稿，请继续编辑或取消后再创建");
        int next = Math.max(current.nextRevision(), history.stream().mapToInt(PiView::getRevision).max().orElse(0) + 1);
        if (series.reserveRevision(base.getSeriesId(), next) != 1) fail("PI_REVISION_FAILED", "PI修订版本分配失败");
        if (mapper.cloneRevision(piId, base.getSeriesId(), next, CurrentOperator.id()) != 1) fail("PI_REVISION_FAILED", "PI修订创建失败");
        long id = mapper.selectInsertedId();
        if (mapper.cloneItems(piId, id) < 1) fail("PI_REVISION_FAILED", "PI明细复制失败");
        if (series.saveRevisionRequest(base.getSeriesId(), CurrentOperator.id(), request.requestId(), hash, id) != 1)
            fail("PI_REVISION_FAILED", "PI修订幂等记录失败");
        if (logs.insert("PI_REVISION_CREATE", "PI:"+id, "创建PI商业修订", CurrentOperator.id(), CurrentOperator.username(),
                "{\"revision\":"+base.getRevision()+"}", "{\"revision\":"+next+",\"previousPiId\":"+piId+"}", CurrentOperator.clientIp(), request.reason().trim()) != 1)
            fail("PI_AUDIT_FAILED", "PI修订审计保存失败");
        return service.get(id);
    }

    @Override public List<PiView> history(long piId) {
        require("PI_READ");
        service.get(piId);
        PiView current = mapper.selectById(piId);
        if (current == null || current.getSeriesId() == null) fail("PI_NOT_FOUND", "PI不存在");
        return mapper.selectRevisions(current.getSeriesId()).stream().peek(v -> {
            v.setItems(List.of()); v.setPayments(List.of()); v.setLogistics(List.of());
            if(!com.bike.server.common.FinancialAuditAccess.allowed()){v.setPaidAmount(null);v.setPaymentStatus(null);}
        }).toList();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void require(String permission) { CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission(permission); }
    private static void fail(String code, String message) { throw new BusinessException(code, message); }
}
