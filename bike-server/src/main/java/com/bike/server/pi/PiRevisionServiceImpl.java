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

    public PiRevisionServiceImpl(PiMapper mapper, PiSeriesMapper series, OperationLogMapper logs,
            ObjectMapper json, PiService service) {
        this.mapper = mapper; this.series = series; this.logs = logs; this.json = json; this.service = service;
    }

    @Override @Transactional
    public PiView create(long piId, PiRevisionRequest request) {
        require("PI_WRITE");
        PiView base = mapper.selectForUpdate(piId);
        if (base == null || base.getSeriesId() == null) fail("PI_NOT_FOUND", "PI不存在或尚未建立系列");
        if (base.getRevision() != request.expectedRevision()) fail("PI_REVISION_CONFLICT", "PI版本已变化，请刷新后重试");
        if (!Set.of("CONFIRMED", "RELEASED").contains(base.getStatus())) fail("PI_REVISION_STATUS", "只有已确认或已放行PI可以创建修订");
        String hash = sha256(base.getSeriesId()+"|"+request.expectedRevision()+"|"+request.reason().trim());
        PiSeriesMapper.RevisionRequest previous = series.revisionRequest(CurrentOperator.id(), request.requestId());
        if (previous != null) {
            if (!hash.equals(previous.payloadHash())) fail("PI_REVISION_REQUEST_CONFLICT", "同一请求标识不能用于不同修订内容");
            return service.get(previous.piId());
        }
        var current = series.lockSeries(base.getSeriesId());
        if (current == null || current.currentPiId() != piId) fail("PI_REVISION_CONFLICT", "该PI已不是当前有效版本");
        int next = current.nextRevision();
        if (mapper.cloneRevision(piId, base.getSeriesId(), next, CurrentOperator.id()) != 1) fail("PI_REVISION_FAILED", "PI修订创建失败");
        long id = mapper.selectInsertedId();
        if (mapper.cloneItems(piId, id) < 1) fail("PI_REVISION_FAILED", "PI明细复制失败");
        if (series.saveRevisionRequest(base.getSeriesId(), CurrentOperator.id(), request.requestId(), hash, id) != 1)
            fail("PI_REVISION_FAILED", "PI修订幂等记录失败");
        logs.insert("PI_REVISION_CREATE", "PI:"+id, "创建PI商业修订", CurrentOperator.id(), CurrentOperator.username(),
                "{\"revision\":"+base.getRevision()+"}", "{\"revision\":"+next+",\"previousPiId\":"+piId+"}", CurrentOperator.clientIp(), request.reason().trim());
        return service.get(id);
    }

    @Override public List<PiView> history(long piId) {
        require("PI_READ");
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
