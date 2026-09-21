package com.bike.server.lock;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.bike.server.auth.UserAuthRecord;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryLockServiceTest {

    @Test
    void lookupByNumberDoesNotDependOnPagination() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView record = lock(101L, 1L, "LOCKED", 10, 0, 0);
        Mockito.when(mapper.selectByNo("LK101")).thenReturn(record);
        assertThat(new InventoryLockServiceImpl(mapper).getByNo(" LK101 ")).isSameAs(record);
        Mockito.verify(mapper).selectByNo("LK101");
        Mockito.verify(mapper, Mockito.never()).selectPage(Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void lookupByNumberRejectsAnotherAgentsRecord() {
        var authentication = new UsernamePasswordAuthenticationToken("dealer", "N/A", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_WEB_AGENT"), new SimpleGrantedAuthority("PERM_AGENT_LOCK_WRITE")));
        authentication.setDetails(new UserAuthRecord(2L, "dealer", "hash", "AGENT", 2L, null, true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.selectByNo("LK101")).thenReturn(lock(101L, 1L, "LOCKED", 10, 0, 0));
        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).getByNo("LK101"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN");
    }

    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listRejectsPdaOperatorEvenWithInventoryLockPermission() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "pda", "N/A", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"),
                new SimpleGrantedAuthority("PERM_AGENT_LOCK_WRITE"))));
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).list(null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("PERMISSION_DENIED");
        Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void releaseRejectsPdaOperatorEvenWithInventoryLockPermission() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "pda", "N/A", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_PDA_OPERATOR"),
                new SimpleGrantedAuthority("PERM_AGENT_LOCK_WRITE"))));
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).release(8L, "业务取消"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("PERMISSION_DENIED");
        Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void createRejectsQuantityBeyondAvailablePhysicalInventory() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.lockModelForUpdate(1L)).thenReturn(1);
        Mockito.when(mapper.selectPhysicalForUpdate(1L)).thenReturn(2L);
        Mockito.when(mapper.selectActiveLockedForUpdate(1L)).thenReturn(1L);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper)
                .create(new InventoryLockCreateRequest(1L, 1L, 2, null, "test")))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("INVENTORY_NOT_ENOUGH");
        Mockito.verify(mapper, Mockito.never()).insert(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void createUsesConfiguredExpiryAndWritesDetail() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.lockModelForUpdate(1L)).thenReturn(1);
        Mockito.when(mapper.selectPhysicalForUpdate(1L)).thenReturn(3L);
        Mockito.when(mapper.selectActiveLockedForUpdate(1L)).thenReturn(0L);
        Mockito.when(mapper.insert(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.isNull())).thenReturn(1);
        Mockito.when(mapper.insertDetail(Mockito.anyString(), Mockito.eq(1L), Mockito.eq(2))).thenReturn(1);
        Mockito.when(mapper.selectIdByNo(Mockito.anyString())).thenReturn(8L);
        InventoryLockView created = lock(8L, 1L, "LOCKED", 2, 0, 0);
        Mockito.when(mapper.selectById(8L)).thenReturn(created);

        InventoryLockView result = new InventoryLockServiceImpl(mapper)
                .create(new InventoryLockCreateRequest(1L, 1L, 2, LocalDateTime.now().plusHours(2), "test"));

        assertThat(result.getId()).isEqualTo(8L);
        Mockito.verify(mapper).insertDetail(Mockito.anyString(), Mockito.eq(1L), Mockito.eq(2));
        Mockito.verify(mapper).insertOperationLog(Mockito.anyString(), Mockito.eq("库存锁定"), Mockito.isNull(),
                Mockito.eq("test-admin"), Mockito.anyString(), Mockito.isNull());
    }

    @Test
    void agentCreateMayOmitAgentIdBecauseScopeComesFromAuthentication() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("agent-a", null, java.util.List.of(
                new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_AGENT_LOCK_WRITE")));
        authentication.setDetails(new UserAuthRecord(11L, "agent-a", "hash", "AGENT", 101L, null, true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.lockModelForUpdate(1L)).thenReturn(1);
        Mockito.when(mapper.selectPhysicalForUpdate(1L)).thenReturn(3L);
        Mockito.when(mapper.selectActiveLockedForUpdate(1L)).thenReturn(0L);
        Mockito.when(mapper.insert(Mockito.anyString(), Mockito.argThat(request -> request.agentId().equals(101L)), Mockito.any(), Mockito.eq(11L))).thenReturn(1);
        Mockito.when(mapper.insertDetail(Mockito.anyString(), Mockito.eq(1L), Mockito.eq(1))).thenReturn(1);
        Mockito.when(mapper.selectIdByNo(Mockito.anyString())).thenReturn(8L);
        Mockito.when(mapper.selectById(8L)).thenReturn(lock(8L, 101L, "LOCKED", 1, 0, 0));
        Mockito.when(mapper.countAuthorizedModel(101L, 1L)).thenReturn(1);
        Mockito.when(mapper.lockAgentAccess(101L)).thenReturn(101L);

        InventoryLockView result = new InventoryLockServiceImpl(mapper)
                .create(new InventoryLockCreateRequest(null, 1L, 1, null, "agent lock"));

        assertThat(result.getAgentId()).isEqualTo(101L);
        Mockito.verify(mapper).insert(Mockito.anyString(), Mockito.argThat(request -> request.agentId().equals(101L)), Mockito.any(), Mockito.eq(11L));
    }

    @Test
    void convertRejectsQuantityAboveRemainingLock() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView lock = lock(8L, 1L, "PARTIAL_CONVERTED", 5, 0, 4);
        Mockito.when(mapper.selectById(8L)).thenReturn(lock);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).convert(8L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过锁定数量");
        Mockito.verify(mapper, Mockito.never()).convert(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void releaseKeepsHistoryAndUpdatesRecord() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView before = lock(8L, 1L, "LOCKED", 5, 0, 0);
        InventoryLockView after = lock(8L, 1L, "RELEASED", 5, 5, 0);
        Mockito.when(mapper.selectById(8L)).thenReturn(before, after);
        Mockito.when(mapper.release(8L, "业务取消")).thenReturn(1);

        InventoryLockView result = new InventoryLockServiceImpl(mapper).release(8L, "业务取消");

        assertThat(result.getStatus()).isEqualTo("RELEASED");
        Mockito.verify(mapper).insertOperationLog("LOCK-8", "释放库存锁定", null, "test-admin", "业务取消", null);
    }

    @Test
    void releaseRejectsExpiredLockBeforeUpdate() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView expired = lock(8L, 1L, "LOCKED", 5, 0, 0);
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        Mockito.when(mapper.selectById(8L)).thenReturn(expired);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).release(8L, "过期测试"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("INVENTORY_LOCK_EXPIRED");
        Mockito.verify(mapper, Mockito.never()).release(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void convertRejectsExpiredLockBeforeUpdate() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView expired = lock(8L, 1L, "LOCKED", 5, 0, 0);
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        Mockito.when(mapper.selectById(8L)).thenReturn(expired);

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).convert(8L, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("INVENTORY_LOCK_EXPIRED");
        Mockito.verify(mapper, Mockito.never()).convert(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void agentListIsRestrictedToOwnAgentId() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("agent-a", null, java.util.List.of(
                new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_AGENT_INVENTORY_READ")));
        authentication.setDetails(new UserAuthRecord(11L, "agent-a", "hash", "AGENT", 101L, null, true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.selectPage(101L, 0L, 20L)).thenReturn(java.util.List.of());
        Mockito.when(mapper.count(101L)).thenReturn(0L);

        new InventoryLockServiceImpl(mapper).list(202L, 1, 20);

        Mockito.verify(mapper).selectPage(101L, 0L, 20L);
        Mockito.verify(mapper).count(101L);
        Mockito.verify(mapper, Mockito.never()).selectPage(202L, 0L, 20L);
    }

    @Test
    void agentCannotReleaseAnotherAgentsLock() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("agent-a", null, java.util.List.of(
                new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_AGENT_LOCK_WRITE")));
        authentication.setDetails(new UserAuthRecord(11L, "agent-a", "hash", "AGENT", 101L, null, true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.selectById(8L)).thenReturn(lock(8L, 202L, "LOCKED", 1, 0, 0));

        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).release(8L, "越权测试"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("FORBIDDEN");
        Mockito.verify(mapper, Mockito.never()).release(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void legacyConversionAuditIsReadOnlyAndPaginatedForInternalOrderReaders() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        var row = new LegacyConversionAuditView(8L, "OLD-8", 1L, "代理商", 1L, "车型", 5, 0, 2,
                "CONVERTED", LocalDateTime.now().plusHours(1), LocalDateTime.now(), "旧版本");
        Mockito.when(mapper.selectLegacyConversions(20L, 20L)).thenReturn(java.util.List.of(row));
        Mockito.when(mapper.countLegacyConversions()).thenReturn(21L);

        var result = new InventoryLockServiceImpl(mapper).legacyConversions(2, 20);

        assertThat(result.records()).containsExactly(row);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(21);
        Mockito.verify(mapper).selectLegacyConversions(20L, 20L);
        Mockito.verify(mapper).countLegacyConversions();
        Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void agentCannotReadLegacyConversionAudit() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "agent", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_ORDER_READ"))));
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        assertThatThrownBy(() -> new InventoryLockServiceImpl(mapper).legacyConversions(1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("FORBIDDEN");
        Mockito.verifyNoInteractions(mapper);
    }

    private static InventoryLockView lock(long id, long agentId, String status, int qty, int released, int converted) {
        InventoryLockView lock = new InventoryLockView();
        lock.setId(id);
        lock.setLockNo("LOCK-" + id);
        lock.setAgentId(agentId);
        lock.setProductModelId(1L);
        lock.setStatus(status);
        lock.setLockQty(qty);
        lock.setReleasedQty(released);
        lock.setConvertedQty(converted);
        lock.setExpireTime(LocalDateTime.now().plusHours(1));
        return lock;
    }
}
