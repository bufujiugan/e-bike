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
    @Autowired private PartPhotoStore photoStore;

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
        return createPart(request, null);
    }

    @Override
    @Transactional
    public PartView createWithPhoto(PartCreateRequest request, PartPhotoUpload photo) {
        return createPart(request, photo);
    }

    @Override
    public PhotoContent getPhoto(long id) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess();
        com.bike.server.common.CurrentOperator.requirePermission("PART_READ");
        var photo = partMapper.selectPhoto(id);
        if (photo == null) throw new BusinessException("PART_PHOTO_NOT_FOUND", "零部件图片不存在");
        return new PhotoContent(photoStore.read(photo), photo.contentType(), photo.fileSize());
    }

    private PartView createPart(PartCreateRequest request, PartPhotoUpload photo) {
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
        if (photo != null) {
            String key = photoStore.save(created.getId(), photo);
            if (partMapper.updatePhoto(created.getId(), key) != 1)
                throw new BusinessException("PART_PHOTO_SAVE_FAILED", "零部件图片关联失败，请重试");
            created.setHasPhoto(true);
        }
        partMapper.insertOperationLog(String.valueOf(created.getId()), "创建零部件", com.bike.server.common.CurrentOperator.id(), com.bike.server.common.CurrentOperator.username(), null, "partSn=" + partSn + ",partType=" + normalized.partType() + ",hasPhoto=" + created.isHasPhoto(), com.bike.server.common.CurrentOperator.clientIp());
        return created;
    }

    @Override
    @Transactional
    public PartView update(long id, PartUpdateRequest request) {
        return updatePart(id, request, null, false);
    }

    @Override
    @Transactional
    public PartView updateWithPhoto(long id, PartUpdateRequest request, PartPhotoUpload photo, boolean removePhoto) {
        return updatePart(id, request, photo, removePhoto);
    }

    private PartView updatePart(long id, PartUpdateRequest request, PartPhotoUpload photo, boolean removePhoto) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requirePermission("PART_WRITE");
        if (photo != null && removePhoto)
            throw new BusinessException("PART_PHOTO_CHANGE_INVALID", "更换图片和移除图片不能同时提交");
        if(productionGuard!=null)productionGuard.lock();
        PartView before = getById(id);
        validateSupplier(request.supplierId());
        if (request.status() != null && !request.status().isBlank() && !request.status().equalsIgnoreCase(before.getStatus())) {
            throw new BusinessException("PART_STATUS_MANAGED", "零部件状态只能由组装、返工等业务流程变更");
        }
        if (partMapper.update(id, request) != 1) throw new BusinessException("PART_UPDATE_FAILED", "零部件更新失败");
        String photoAction = "更新零部件";
        if (photo != null || removePhoto) {
            String previousKey = partMapper.selectPhotoKeyForUpdate(id);
            if (photo != null || previousKey != null) {
                String newKey = photo == null ? null : photoStore.save(id, photo);
                if (partMapper.updatePhoto(id, newKey) != 1)
                    throw new BusinessException("PART_PHOTO_SAVE_FAILED", "零部件图片关联失败，请重试");
                // 旧图片在关联变更提交后才可被后台清理，回滚时仍可访问。
                if (previousKey != null) photoStore.queueCleanup(previousKey);
                photoAction = photo == null ? "移除零部件图片" : previousKey == null ? "添加零部件图片" : "更换零部件图片";
            }
        }
        PartView updated = getById(id);
        partMapper.insertOperationLog(String.valueOf(id), photoAction, com.bike.server.common.CurrentOperator.id(),
                com.bike.server.common.CurrentOperator.username(), photoAudit(before), photoAudit(updated),
                com.bike.server.common.CurrentOperator.clientIp());
        return updated;
    }

    private static String photoAudit(PartView part) {
        return "batchNo=" + part.getBatchNo() + ",qcStatus=" + part.getQcStatus() + ",hasPhoto=" + part.isHasPhoto();
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
