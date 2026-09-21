package com.bike.server.part;

import com.bike.server.common.BusinessException;
import com.bike.server.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.bike.server.validation.SnRuleService;

import java.util.Locale;

@Service
public class PartServiceImpl implements PartService {
    private final PartMapper partMapper;
    private final SnRuleService snRuleService;
    @Autowired private com.bike.server.production.ProductionGuard productionGuard;

    public PartServiceImpl(PartMapper partMapper) {
        this(partMapper, new com.bike.server.validation.SnRuleServiceImpl(null));
    }

    @Autowired
    public PartServiceImpl(PartMapper partMapper, SnRuleService snRuleService) {
        this.partMapper = partMapper;
        this.snRuleService = snRuleService;
    }

    @Override
    public PageResponse<PartView> list(String keyword, String partType, String status, Long supplierId, long page, long pageSize) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("PART_READ", "INVENTORY_READ");
        long normalizedPage = Math.max(1, page);
        long normalizedSize = Math.min(100, Math.max(1, pageSize));
        long offset = (normalizedPage - 1) * normalizedSize;
        String normalizedKeyword = clean(keyword), normalizedType = clean(partType), normalizedStatus = clean(status);
        return new PageResponse<>(partMapper.selectPage(normalizedKeyword, normalizedType, normalizedStatus, supplierId, offset, normalizedSize),
                normalizedPage, normalizedSize, partMapper.count(normalizedKeyword, normalizedType, normalizedStatus, supplierId));
    }

    @Override
    public PartView getById(long id) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("PART_READ", "INVENTORY_READ", "TRACE_READ");
        PartView part = partMapper.selectById(id);
        if (part == null) throw new BusinessException("PART_NOT_FOUND", "零部件不存在");
        return part;
    }

    @Override
    public PartView getBySn(String partSn) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("PART_READ", "INVENTORY_READ", "TRACE_READ");
        PartView part = partMapper.selectBySn(snRuleService.normalizePartSn(partSn, null));
        if (part == null) throw new BusinessException("PART_NOT_FOUND", "零部件不存在");
        return part;
    }

    @Override
    @Transactional
    public PartView create(PartCreateRequest request) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requirePermission("PART_WRITE");
        String partType = request.partType().trim().toUpperCase(Locale.ROOT);
        String partSn = snRuleService.normalizePartSn(request.partSn(), partType);
        validateSupplier(request.supplierId());
        if (partMapper.selectBySn(partSn) != null) {
            throw new BusinessException("PART_ALREADY_EXISTS", "零部件 SN 已存在");
        }
        PartCreateRequest normalized = new PartCreateRequest(partSn, partType, request.supplierId(),
                request.batchNo(), request.qcStatus() == null ? "PASSED" : request.qcStatus(), request.remark());
        if (partMapper.insert(normalized) != 1) throw new BusinessException("PART_CREATE_FAILED", "零部件创建失败");
        PartView created = partMapper.selectBySn(partSn);
        partMapper.insertOperationLog(String.valueOf(created.getId()), "创建零部件", com.bike.server.common.CurrentOperator.id(), com.bike.server.common.CurrentOperator.username(), null, "partSn=" + partSn + ",partType=" + normalized.partType(), com.bike.server.common.CurrentOperator.clientIp());
        return created;
    }

    @Override
    @Transactional
    public PartView update(long id, PartUpdateRequest request) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requirePermission("PART_WRITE");
        if(productionGuard!=null)productionGuard.lock();
        PartView before = getById(id);
        validateSupplier(request.supplierId());
        if (request.status() != null && !request.status().isBlank() && !request.status().equalsIgnoreCase(before.getStatus())) {
            throw new BusinessException("PART_STATUS_MANAGED", "零部件状态只能由组装、返工等业务流程变更");
        }
        if (partMapper.update(id, request) != 1) throw new BusinessException("PART_UPDATE_FAILED", "零部件更新失败");
        PartView updated = getById(id);
        partMapper.insertOperationLog(String.valueOf(id), "更新零部件", com.bike.server.common.CurrentOperator.id(), com.bike.server.common.CurrentOperator.username(), "batchNo=" + before.getBatchNo() + ",qcStatus=" + before.getQcStatus(), "batchNo=" + updated.getBatchNo() + ",qcStatus=" + updated.getQcStatus(), com.bike.server.common.CurrentOperator.clientIp());
        return updated;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateSupplier(Long supplierId) {
        if (supplierId != null && (supplierId <= 0 || partMapper.countEnabledSupplier(supplierId) != 1)) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "供应商不存在或已停用");
        }
    }
}
