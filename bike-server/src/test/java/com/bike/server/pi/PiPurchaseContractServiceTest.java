package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class PiPurchaseContractServiceTest {
    private final PiPurchaseContractMapper mapper = mock(PiPurchaseContractMapper.class);
    private final PiSeriesMapper series = mock(PiSeriesMapper.class);
    private final PiPurchaseContractService service = new PiPurchaseContractServiceImpl(mapper, series, new ObjectMapper().findAndRegisterModules());

    @Test void orderUsesCurrentPiAndInheritsNearestConfirmedAncestor() {
        when(series.currentPiForOrder(7L)).thenReturn(30L);
        when(mapper.selectRevisionContracts(List.of(30L))).thenReturn(List.of(
                revision(30, 30, 0, "PENDING", "UNCONFIRMED"),
                revision(30, 20, 1, "COMPLETED", "BUY-20"),
                revision(30, 10, 2, "COMPLETED", "BUY-10")));
        assertThat(service.findForOrder(7L)).isEqualTo(new PiPurchaseContract("SAVA", "BUY-20"));
    }

    @Test void stockRecordRemainsBoundToExecutionRevisionAndUsesBatchQueries() {
        when(mapper.selectStockExecutions(List.of("OUT-1", "OUT-2", "OUT-3"))).thenReturn(List.of(
                new PiPurchaseContractMapper.StockExecution("OUT-1", 10L),
                new PiPurchaseContractMapper.StockExecution("OUT-2", 20L),
                new PiPurchaseContractMapper.StockExecution("OUT-3", 10L)));
        when(mapper.selectRevisionContracts(List.of(10L, 20L))).thenReturn(List.of(
                revision(10, 10, 0, "COMPLETED", "OLD-NO"),
                revision(20, 20, 0, "COMPLETED", "NEW-NO"),
                revision(20, 10, 1, "COMPLETED", "OLD-NO")));
        var contracts = service.findForStockRecords(List.of("OUT-1", "OUT-2", "OUT-3"));
        assertThat(contracts.get("OUT-1").purchaseContractNo()).isEqualTo("OLD-NO");
        assertThat(contracts.get("OUT-2").purchaseContractNo()).isEqualTo("NEW-NO");
        assertThat(contracts.get("OUT-3").purchaseContractNo()).isEqualTo("OLD-NO");
        verifyNoInteractions(series);
        verify(mapper, times(1)).selectRevisionContracts(anyList());
    }

    @Test void historicalRecordsWithoutExecutionPiDoNotGuessCurrentContract() {
        when(mapper.selectStockExecutions(List.of("OUT-OLD"))).thenReturn(List.of(new PiPurchaseContractMapper.StockExecution("OUT-OLD", null)));
        assertThat(service.findForStockRecords(List.of("OUT-OLD"))).isEmpty();
        verify(mapper, never()).selectRevisionContracts(anyList());
        verifyNoInteractions(series);
    }

    @Test void emptyCollectionsAndUnlinkedOrdersNeedNoContractQuery() {
        when(series.currentPiForOrder(7L)).thenReturn(null);
        assertThat(service.findForPis(List.of())).isEmpty();
        assertThat(service.findForStockRecords(List.of())).isEmpty();
        assertThat(service.findForOrder(7L)).isNull();
        verifyNoInteractions(mapper);
    }

    @Test void confirmedEmptyRecordDoesNotFallBackToOlderContract() {
        when(mapper.selectRevisionContracts(List.of(20L))).thenReturn(List.of(
                new PiPurchaseContractMapper.RevisionContract(20, 20, 0, "COMPLETED", null),
                revision(20, 10, 1, "COMPLETED", "OLD-NO")));
        assertThat(service.findForPis(List.of(20L))).isEmpty();
    }

    @Test void invalidSavedJsonFailsWithOriginalCause() {
        when(mapper.selectRevisionContracts(List.of(20L))).thenReturn(List.of(
                new PiPurchaseContractMapper.RevisionContract(20, 20, 0, "COMPLETED", "{broken")));
        assertThatThrownBy(() -> service.findForPis(List.of(20L))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("piId=20").hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    private PiPurchaseContractMapper.RevisionContract revision(long piId, long sourceId, int depth, String status, String number) {
        return new PiPurchaseContractMapper.RevisionContract(piId, sourceId, depth, status,
                "{\"purchaseContractParty\":\"SAVA\",\"purchaseContractNo\":\"" + number + "\"}");
    }
}
