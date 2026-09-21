package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class AfterSalesAttachmentMysqlTest {
    @TempDir Path temp;
    private final AfterSalesUploadValidator validator = new AfterSalesUploadValidator(new AfterSalesVideoValidator(8_294_400, 600, 72000));

    @Test void actualFilesRoundTripWithScopedMetadataVisibilityAndMessageLinks() throws Exception {
        var files = storage();
        try (var f = fixture()) {
            var service = new AfterSalesAttachmentService(f.mapper, files, validator);
            long id = f.agent(() -> f.service.create(request(null, null, null))).getId();
            var inputs = List.<MultipartFile>of(
                file("故障.png", "image/png", AfterSalesTestFiles.image("png")), file("proof.pdf", "application/pdf", AfterSalesTestFiles.pdf()),
                file("fault.mp4", "video/mp4", AfterSalesTestFiles.video(temp)), file("diagnosis.json", "application/json", "{\"code\":\"E01\"}".getBytes(StandardCharsets.UTF_8)));
            var uploaded = f.agent(() -> upload(service, id, inputs, "CUSTOMER", "DIAGNOSTIC", null));
            assertThat(uploaded).hasSize(4);
            for (int index = 0; index < uploaded.size(); index++) {
                var attachment = uploaded.get(index);
                var download = f.agent(() -> service.download(id, attachment.id()));
                try (var input = download.resource().getInputStream()) { assertThat(input.readAllBytes()).isEqualTo(inputs.get(index).getBytes()); }
                assertThat(attachment.sha256()).hasSize(64);
            }
            assertThat(f.agent(() -> service.list(id))).hasSize(4);
            assertThat(f.agent(() -> f.service.history(id))).extracting(AfterSalesHistoryView::action).contains("ATTACHMENTS_ADDED");
            f.agent(() -> f.service.submit(id));
            var message = f.agent(() -> f.service.addMessage(id, new AfterSalesMessageRequest("带诊断附件的留言", "CUSTOMER")));
            var linked = f.agent(() -> upload(service, id, List.of(file("report.log", "text/plain", "E01".getBytes())), "CUSTOMER", "DIAGNOSTIC", message.id()));
            assertThat(linked.getFirst().messageId()).isEqualTo(message.id());
            var internal = f.admin(() -> upload(service, id, List.of(file("internal.log", "text/plain", "private".getBytes())), "INTERNAL", "TECHNICAL", null)).getFirst();
            assertThat(f.agent(() -> service.list(id))).hasSize(5);
            assertThat(f.admin(() -> service.list(id))).hasSize(6);
            assertCode("FORBIDDEN", () -> f.agent(() -> service.download(id, internal.id())));
            assertCode("FORBIDDEN", () -> f.as("AGENT", 99L, () -> service.download(id, uploaded.getFirst().id()), "ROLE_WEB_AGENT", "PERM_AFTER_SALES_READ"));
            assertThat(f.agent(() -> f.service.history(id))).extracting(AfterSalesHistoryView::action).doesNotContain("INTERNAL_ATTACHMENTS_ADDED");
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(uploaded)).doesNotContain("objectKey", "local:", "cos:");
        } finally { files.close(); }
    }

    @Test void invalidBatchAndReadOnlyPdaOrInternalScopeAttemptsCreateNoObjects() throws Exception {
        var files = storage();
        try (var f = fixture()) {
            var service = new AfterSalesAttachmentService(f.mapper, files, validator);
            long id = f.submittedCase();
            var valid = file("valid.png", "image/png", AfterSalesTestFiles.image("png"));
            assertCode("ATTACHMENT_CONTENT_INVALID", () -> f.agent(() -> upload(service, id, List.of(valid, file("fake.pdf", "application/pdf", "fake".getBytes())), "CUSTOMER", "OTHER", null)));
            assertCode("PERMISSION_DENIED", () -> f.agent(() -> upload(service, id, List.of(valid), "INTERNAL", "TECHNICAL", null)));
            assertCode("PERMISSION_DENIED", () -> f.as("WEB_OPERATOR", null, () -> upload(service, id, List.of(valid), "CUSTOMER", "OTHER", null), "PERM_AFTER_SALES_READ"));
            assertCode("PERMISSION_DENIED", () -> f.as("PDA_OPERATOR", null, () -> service.list(id), "ROLE_SUPER_ADMIN"));
            assertCode("PERMISSION_DENIED", () -> f.as("PDA_OPERATOR", null, () -> upload(service, id, List.of(valid), "CUSTOMER", "OTHER", null), "ROLE_SUPER_ADMIN"));
            assertThat(f.count("after_sales_attachment")).isZero(); assertStorageEmpty();
        } finally { files.close(); }
    }

    @Test void salesScopeAppliesToAttachmentListAndDownloadAndIsRevokedImmediately() throws Exception {
        var files=storage();
        try(var f=fixture()) {
            var service=new AfterSalesAttachmentService(f.mapper,files,validator);
            var companies=new AfterSalesCompanyService(f.mapper); long id=f.submittedCase();
            var attachment=f.agent(()->upload(service,id,List.of(file("diagnostic.log","text/plain","scope".getBytes())),"CUSTOMER","DIAGNOSTIC",null)).getFirst();
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.list(id),"PERM_AFTER_SALES_READ"));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.download(id,attachment.id()),"PERM_AFTER_SALES_READ"));
            f.admin(()->companies.setSalespeople(8,List.of(7L)));
            assertThat(f.as("WEB_OPERATOR",null,()->service.list(id),"PERM_AFTER_SALES_READ")).hasSize(1);
            try(var input=f.as("WEB_OPERATOR",null,()->service.download(id,attachment.id()),"PERM_AFTER_SALES_READ").resource().getInputStream()) { assertThat(input.readAllBytes()).isEqualTo("scope".getBytes()); }
            f.admin(()->companies.setSalespeople(8,List.of()));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.list(id),"PERM_AFTER_SALES_READ"));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.download(id,attachment.id()),"PERM_AFTER_SALES_READ"));
        } finally { files.close(); }
    }

    @Test void attachmentCannotBeAddedToAnotherCasesOrAnotherAuthorsMessage() throws Exception {
        var files = storage();
        try (var f = fixture()) {
            var service = new AfterSalesAttachmentService(f.mapper, files, validator);
            long first = f.submittedCase(), second = f.submittedCase();
            var message = f.agent(() -> f.service.addMessage(first, new AfterSalesMessageRequest("message", "CUSTOMER")));
            var input = List.<MultipartFile>of(file("diagnostic.log", "text/plain", "E01".getBytes()));
            assertCode("INVALID_PARAMETER", () -> f.agent(() -> upload(service, second, input, "CUSTOMER", "DIAGNOSTIC", message.id())));
            f.sql("UPDATE " + f.prefix + "after_sales_message SET author_id=999 WHERE id=" + message.id());
            assertCode("FORBIDDEN", () -> f.agent(() -> upload(service, first, input, "CUSTOMER", "DIAGNOSTIC", message.id())));
            assertThat(f.count("after_sales_attachment")).isZero(); assertStorageEmpty();
        } finally { files.close(); }
    }

    @Test void failedAuditRollsBackMetadataAndRemovesAllWrittenObjects() throws Exception {
        var files = storage();
        try (var f = fixture()) {
            var brokenMapper = proxy(f.mapper, (method, arguments) -> method.equals("insertAudit") ? 0 : NO_OVERRIDE);
            var service = new AfterSalesAttachmentService(brokenMapper, files, validator);
            long id = f.submittedCase();
            var inputs = List.<MultipartFile>of(file("first.log", "text/plain", "E01".getBytes()), file("second.log", "text/plain", "E02".getBytes()));
            assertCode("AFTER_SALES_AUDIT_FAILED", () -> f.agent(() -> upload(service, id, inputs, "CUSTOMER", "DIAGNOSTIC", null)));
            assertThat(f.count("after_sales_attachment")).isZero(); assertStorageEmpty();
            assertThat(f.admin(() -> f.service.history(id))).hasSize(2);
        } finally { files.close(); }
    }

    @Test void partialStorageFailureCleansCompletedAndPartiallyWrittenFiles() throws Exception {
        var files = new ProductionFileStore(temp.resolve("store").toString(), "local", "", "", "", "") {
            private int writes;
            @Override public void write(String key, InputStream input, long size, String type) throws IOException {
                super.write(key, input, size, type);
                if (++writes == 2) throw new IOException("simulated storage write failure");
            }
        };
        try (var f = fixture()) {
            var service = new AfterSalesAttachmentService(f.mapper, files, validator); long id = f.submittedCase();
            var inputs = List.<MultipartFile>of(file("first.log", "text/plain", "E01".getBytes()), file("second.log", "text/plain", "E02".getBytes()));
            assertThatThrownBy(() -> f.agent(() -> upload(service, id, inputs, "CUSTOMER", "DIAGNOSTIC", null))).isInstanceOf(UncheckedIOException.class);
            assertThat(f.count("after_sales_attachment")).isZero(); assertStorageEmpty();
        } finally { files.close(); }
    }

    @Test void concurrentCaseClosurePreventsUploadAfterCaseRowLockIsReleased() throws Exception {
        var files = storage();
        try (var f = fixture(); var workers = Executors.newFixedThreadPool(2)) {
            long id = f.submittedCase(); f.sql("UPDATE " + f.prefix + "after_sales_case SET status='RESOLVED' WHERE id=" + id);
            var locked = new CountDownLatch(1); var commit = new CountDownLatch(1); var uploadStarted = new CountDownLatch(1);
            Future<?> close = workers.submit(() -> f.agent(() -> { f.mapper.selectForUpdate(id); locked.countDown(); await(commit); return f.service.close(id); }));
            try {
                await(locked);
                var waiting = new AfterSalesAttachmentService(proxy(f.mapper, (method, arguments) -> { if (method.equals("selectForUpdate")) uploadStarted.countDown(); return NO_OVERRIDE; }), files, validator);
                Future<String> upload = workers.submit(() -> {
                    try { f.agent(() -> upload(waiting, id, List.of(file("report.log", "text/plain", "E01".getBytes())), "CUSTOMER", "DIAGNOSTIC", null)); return "UNEXPECTED_SUCCESS"; }
                    catch (BusinessException error) { return error.getCode(); }
                });
                await(uploadStarted); assertThatThrownBy(() -> upload.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown(); close.get(10, TimeUnit.SECONDS);
                assertThat(upload.get(10, TimeUnit.SECONDS)).isEqualTo("INVALID_STATUS_TRANSITION");
                assertThat(f.count("after_sales_attachment")).isZero(); assertStorageEmpty();
            } finally { commit.countDown(); }
        } finally { files.close(); }
    }
    private AfterSalesMysqlTest.Fixture fixture() throws Exception {
        var fixture = new AfterSalesMysqlTest.Fixture();
        try (var input = Resources.getResourceAsStream("db/migration/V26__after_sales_attachment_links.sql")) {
            fixture.sql(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", fixture.prefix));
            return fixture;
        } catch (Exception error) { fixture.close(); throw error; }
    }
    private ProductionFileStore storage() { return new ProductionFileStore(temp.resolve("store").toString(), "local", "", "", "", ""); }
    private void assertStorageEmpty() throws IOException { if (Files.exists(temp.resolve("store"))) try (var files = Files.list(temp.resolve("store"))) { assertThat(files.toList()).isEmpty(); } }
    private static MockMultipartFile file(String name, String type, byte[] bytes) { return new MockMultipartFile("files", name, type, bytes); }
    private static List<AfterSalesAttachmentView> upload(AfterSalesAttachmentService service, long id, List<MultipartFile> files, String visibility, String category, Long messageId) {
        try { return service.upload(id, files, visibility, category, messageId); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static final Object NO_OVERRIDE = new Object();
    private interface OverrideCall { Object call(String method, Object[] arguments); }
    private static AfterSalesMapper proxy(AfterSalesMapper mapper, OverrideCall interceptor) {
        return (AfterSalesMapper) Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(), new Class<?>[]{AfterSalesMapper.class}, (self, method, args) -> {
            Object intercepted = interceptor.call(method.getName(), args); if (intercepted != NO_OVERRIDE) return intercepted;
            try { return method.invoke(mapper, args); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void await(CountDownLatch latch) { try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
}
