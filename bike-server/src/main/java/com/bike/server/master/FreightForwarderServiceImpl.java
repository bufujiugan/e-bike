package com.bike.server.master;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FreightForwarderServiceImpl implements FreightForwarderService {
    private final FreightForwarderMapper mapper;
    private final OperationLogMapper operationLogs;

    public FreightForwarderServiceImpl(FreightForwarderMapper mapper, OperationLogMapper operationLogs) {
        this.mapper = mapper;
        this.operationLogs = operationLogs;
    }

    @Override
    public List<FreightForwarder> listForwarders() {
        requireDirectoryAccess();
        return mapper.listForwarders();
    }

    @Override
    @Transactional
    public FreightForwarder createForwarder(FreightForwarderRequest request) {
        requireDirectoryAccess();
        if (mapper.insertForwarder(request.normalized()) != 1) {
            throw new BusinessException("FREIGHT_FORWARDER_SAVE_FAILED", "货代信息保存失败");
        }
        long id = mapper.selectInsertedId();
        recordChange(id, "新增货代信息");
        return requireForwarder(id);
    }

    @Override
    @Transactional
    public FreightForwarder updateForwarder(long id, int revision, FreightForwarderRequest request) {
        requireDirectoryAccess();
        // 版本条件防止两位经办人同时编辑时覆盖对方已保存的资料。
        if (mapper.updateForwarder(id, revision, request.normalized()) != 1) {
            requireForwarder(id);
            throw new BusinessException("FREIGHT_FORWARDER_CONFLICT", "货代信息已更新，请刷新后重新核对");
        }
        recordChange(id, "更新货代信息");
        return requireForwarder(id);
    }

    private FreightForwarder requireForwarder(long id) {
        FreightForwarder forwarder = mapper.selectForwarder(id);
        if (forwarder == null) {
            throw new BusinessException("FREIGHT_FORWARDER_NOT_FOUND", "货代信息不存在");
        }
        return forwarder;
    }

    private void recordChange(long id, String action) {
        if (operationLogs.insert("FREIGHT_FORWARDER", Long.toString(id), action, CurrentOperator.id(),
                CurrentOperator.username(), null, null, CurrentOperator.clientIp(), null) != 1) {
            throw new BusinessException("FREIGHT_FORWARDER_SAVE_FAILED", "货代信息保存失败");
        }
    }

    private static void requireDirectoryAccess() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("PI_WRITE");
    }
}
