package com.bike.server.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DispatchNotificationMapper {
    record Audience(long userId, Long customerScopeUserId, boolean orderAllowed, boolean piAllowed) {}

    void insertNotification(@Param("kind") DispatchNotification.Kind kind, @Param("sourceId") long sourceId,
                            @Param("customerId") long customerId, @Param("documentId") long documentId,
                            @Param("documentNo") String documentNo, @Param("batchNo") String batchNo,
                            @Param("quantity") int quantity, @Param("dispatchedAt") LocalDateTime dispatchedAt);

    List<DispatchNotification> selectNotifications(@Param("audience") Audience audience,
                                                  @Param("unreadOnly") boolean unreadOnly,
                                                  @Param("offset") long offset, @Param("limit") int limit);

    long countNotifications(@Param("audience") Audience audience, @Param("unreadOnly") boolean unreadOnly);

    DispatchNotification selectNextUnread(@Param("audience") Audience audience, @Param("afterId") long afterId);

    Long selectVisibleCustomer(@Param("audience") Audience audience, @Param("id") long id);

    int insertReadReceipt(@Param("userId") long userId, @Param("id") long id);
}
