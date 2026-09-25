package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class PiExportWorkerTransactionTest {
    @TempDir Path directory;

    /** 模拟归档绑定的提交应答丢失；Spring 必须通知 UNKNOWN，不能据普通读取决定删除。 */
    static class CommitOutcomeManager extends AbstractPlatformTransactionManager {
        final boolean unknownBindingCommit;
        int commits;
        CommitOutcomeManager(boolean unknownBindingCommit) { this.unknownBindingCommit = unknownBindingCommit; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (++commits == 2 && unknownBindingCommit) throw new TransactionSystemException("Commit acknowledgement lost");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    @Test void unknownCommitKeepsObjectEvenWhenOrdinaryReadHasNoBinding() throws Exception {
        verifyOutcome(true);
    }

    @Test void confirmedBindingRollbackRemovesObject() throws Exception {
        verifyOutcome(false);
    }

    private void verifyOutcome(boolean unknownCommit) throws Exception {
        var mapper = mock(PiExportMapper.class);
        var renderer = mock(PiDocumentService.class);
        byte[] bytes = "archive-pdf".getBytes(StandardCharsets.UTF_8);
        var unbound = new PiExportJob(1L, "TEST", "{}", "PROCESSING", 1L, 1, "claim",
                null, null, null, null, null, null, null, null, null, null);
        when(mapper.next()).thenReturn(1L);
        when(mapper.claim(eq(1L), anyString())).thenReturn(1);
        when(mapper.get(1L)).thenReturn(unbound);
        when(renderer.pdf(any())).thenReturn(bytes);
        when(mapper.saveFile(eq(1L), eq("claim"), eq("pdf"), anyString(), anyString(), eq((long) bytes.length)))
                .thenReturn(unknownCommit ? 1 : 0);
        try (var files = spy(new ProductionFileStore(directory.toString(), "local", "", "", "", ""));
             var worker = new PiExportWorker(mapper, renderer, files, new ObjectMapper(), new CommitOutcomeManager(unknownCommit))) {
            assertThat(worker.processNext()).isTrue();
            var key = ArgumentCaptor.forClass(String.class);
            verify(mapper).saveFile(eq(1L), eq("claim"), eq("pdf"), key.capture(), anyString(), eq((long) bytes.length));
            if (unknownCommit) {
                verify(files, never()).remove(anyString());
                assertThat(files.exists(key.getValue())).isTrue();
                try (var input = files.read(key.getValue()).getInputStream()) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
            } else {
                verify(files).remove(key.getValue());
                assertThat(files.exists(key.getValue())).isFalse();
            }
            verify(mapper).fail(1L, "claim", "PI_EXPORT_GENERATION_FAILED");
        }
    }
}
