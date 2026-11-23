package com.bike.server.notification;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.CustomerScopeAccess;
import com.bike.server.common.PageResponse;
import com.bike.server.pi.PiDispatchBatch;
import com.bike.server.stock.StockRecordView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchNotificationServiceImpl implements DispatchNotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(DispatchNotificationServiceImpl.class);
    private static final int PAGE_SIZE = 20;

    private final DispatchNotificationMapper mapper;
    private final CustomerScopeAccess customerScope;

    public DispatchNotificationServiceImpl(DispatchNotificationMapper mapper, CustomerScopeAccess customerScope) {
        this.mapper = mapper;
        this.customerScope = customerScope;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOrderDispatch(StockRecordView firstRecord, int quantity) {
        // 普通出库一次可包含多辆车，使用首条出库流水作为整次通知的唯一来源。
        mapper.insertNotification(DispatchNotification.Kind.ORDER, firstRecord.getId(), firstRecord.getCustomerId(),
                firstRecord.getOrderId(), firstRecord.getOrderNo(), null, quantity, firstRecord.getOperationTime());
        LOG.debug("写入订单出库通知 orderId={} stockOutId={}", firstRecord.getOrderId(), firstRecord.getId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPiDispatch(PiDispatchBatch batch, String piNo) {
        mapper.insertNotification(DispatchNotification.Kind.PI, batch.id(), batch.customerId(),
                batch.executionPiId(), piNo, batch.batchNo(), batch.activeQty(), batch.createdAt());
        LOG.debug("写入 PI 出库通知 piId={} batchId={}", batch.executionPiId(), batch.id());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<DispatchNotification> list(boolean unreadOnly, int page) {
        var audience = currentAudience();
        int requestedPage = Math.max(1, page);
        long total = mapper.countNotifications(audience, unreadOnly);
        int lastPage = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        int currentPage = Math.min(requestedPage, lastPage);
        long offset = (currentPage - 1L) * PAGE_SIZE;
        return new PageResponse<>(mapper.selectNotifications(audience, unreadOnly, offset, PAGE_SIZE),
                currentPage, PAGE_SIZE, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Pending pending(long afterId) {
        var audience = currentAudience();
        long unreadCount = mapper.countNotifications(audience, true);
        var next = unreadCount == 0 ? null : mapper.selectNextUnread(audience, Math.max(0, afterId));
        return new Pending(unreadCount, next);
    }

    @Override
    @Transactional
    public void markRead(long id) {
        var audience = currentAudience();
        Long customerId = mapper.selectVisibleCustomer(audience, id);
        if (customerId == null) throw new BusinessException("DISPATCH_NOTICE_NOT_FOUND", "当前通知不可查看");
        customerScope.requireCustomer(customerId);
        mapper.insertReadReceipt(audience.userId(), id);
        LOG.debug("确认出库通知已读 userId={} notificationId={}", audience.userId(), id);
    }

    private DispatchNotificationMapper.Audience currentAudience() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requireAnyPermission("ORDER_READ", "PI_READ");
        Long userId = CurrentOperator.id();
        if (userId == null) throw new BusinessException("UNAUTHORIZED", "请先登录");
        boolean superAdmin = CurrentOperator.hasRole("SUPER_ADMIN");
        return new DispatchNotificationMapper.Audience(userId, CurrentOperator.customerScopeUserId(),
                superAdmin || CurrentOperator.hasPermission("ORDER_READ"),
                superAdmin || CurrentOperator.hasPermission("PI_READ"));
    }
}
