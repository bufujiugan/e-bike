package com.bike.server.pi;

import com.bike.server.common.OperationLogMapper;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;
import static com.bike.server.pi.PiReceiptAttachmentTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class PiReceiptAttachmentMysqlTest {
    @TempDir Path temp;

    @Test void genuineReceiptsRoundTripAndIdempotencyNeverChangesPaymentOrPiState() throws Exception {
        try (var f = new Fixture()) {
            long piId = f.core.confirmed(), paymentId = f.receipt(piId);
            var service = f.service(storage(), f.mapper, f.core.logs);
            String before = f.state(piId);
            var png = file("银行回单.png", "image/png", image());
            var saved = f.finance(() -> upload(service, piId, paymentId, "receipt-first", png));
            assertThat(f.finance(() -> upload(service, piId, paymentId, "receipt-first", png))).isEqualTo(saved);
            byte[] pdfBytes = pdf();
            var document = f.finance(() -> upload(service, piId, paymentId, "receipt-pdf", file("银行回单.pdf", "application/pdf", pdfBytes)));
            for (var pair : List.of(Map.entry(saved, png.getBytes()), Map.entry(document, pdfBytes))) {
                var download = f.finance(() -> service.download(piId, paymentId, pair.getKey().id()));
                try (var input = download.resource().getInputStream()) { assertThat(input.readAllBytes()).isEqualTo(pair.getValue()); }
            }
            assertThat(f.finance(() -> service.list(piId, paymentId))).hasSize(2);
            assertThat(f.core.json.writeValueAsString(saved)).doesNotContain("objectKey", "local:", "cos:", "requestId");
            assertThat(f.count()).isEqualTo(2); assertThat(f.auditCount()).isEqualTo(2); assertFiles(2);
            assertCode("IDEMPOTENCY_CONFLICT", () -> f.finance(() -> upload(service, piId, paymentId, "receipt-first", file("another.png", "image/png", image()))));
            assertThat(f.state(piId)).isEqualTo(before);
            assertThat(f.count()).isEqualTo(2); assertFiles(2);
        }
    }

    @Test void mismatchedPiPaymentOrAttachmentAndUnconfirmedReceiptCannotAccessFiles() throws Exception {
        try (var f = new Fixture()) {
            long firstPi = f.core.confirmed(), firstPayment = f.receipt(firstPi);
            long secondPi = f.core.confirmed(), secondPayment = f.receipt(secondPi);
            var service = f.service(storage(), f.mapper, f.core.logs);
            var saved = f.finance(() -> upload(service, firstPi, firstPayment, "receipt", file("proof.png", "image/png", image())));
            assertCode("ATTACHMENT_NOT_FOUND", () -> f.finance(() -> service.list(secondPi, firstPayment)));
            assertCode("ATTACHMENT_NOT_FOUND", () -> f.finance(() -> service.download(secondPi, firstPayment, saved.id())));
            assertCode("ATTACHMENT_NOT_FOUND", () -> f.finance(() -> service.download(secondPi, secondPayment, saved.id())));
            assertCode("ATTACHMENT_NOT_FOUND", () -> f.finance(() -> upload(service, firstPi, secondPayment, "mismatch", file("proof.png", "image/png", image()))));
            f.core.execute("UPDATE " + f.core.prefix + "pi_payment SET status='VOIDED' WHERE id=" + secondPayment);
            assertCode("INVALID_STATUS_TRANSITION", () -> f.finance(() -> upload(service, secondPi, secondPayment, "voided", file("proof.png", "image/png", image()))));
            assertThat(f.count()).isEqualTo(1); assertFiles(1);
        }
    }

    @Test void webpAndWorkbookRoundTripAndCorruptionFailsBeforeReturningBytes() throws Exception {
        try (var f = new Fixture()) {
            long piId=f.core.confirmed(),paymentId=f.receipt(piId);var service=f.service(storage(),f.mapper,f.core.logs);
            var inputs=List.of(file("proof.webp","image/webp",com.bike.server.common.BusinessUploadValidatorTest.webp()),
                file("proof.xlsx",com.bike.server.common.BusinessUploadValidator.XLSX_TYPE,com.bike.server.common.BusinessUploadValidatorTest.xlsx()));
            for(var input:inputs) {
                var saved=f.finance(()->upload(service,piId,paymentId,input.getOriginalFilename().replace(".","-"),input));
                var download=f.finance(()->service.download(piId,paymentId,saved.id()));
                assertThat(download.resource().getContentAsByteArray()).isEqualTo(input.getBytes());
                var stored=f.mapper.selectStored(paymentId,saved.id());
                byte[] damaged=input.getBytes().clone();damaged[damaged.length-1]^=1;Files.write(temp.resolve(stored.objectKey().substring(6)),damaged);
                assertCode("PI_ATTACHMENT_INTEGRITY_FAILED",()->f.finance(()->service.download(piId,paymentId,saved.id())));
            }
            f.core.execute("UPDATE "+f.core.prefix+"pi_payment SET status='REVERSED' WHERE id="+paymentId);
            assertThat(f.finance(()->upload(service,piId,paymentId,inputs.getFirst().getOriginalFilename().replace(".","-"),inputs.getFirst())).id()).isNotBlank();
            assertCode("INVALID_STATUS_TRANSITION",()->f.finance(()->upload(service,piId,paymentId,"new",inputs.getFirst())));
            assertThat(f.count()).isEqualTo(2);
        }
    }

    @Test void insertAuditAndOuterTransactionFailureRollbackBothObjectsAndMetadata() throws Exception {
        try (var f = new Fixture()) {
            long piId = f.core.confirmed(), paymentId = f.receipt(piId);
            var png = file("proof.png", "image/png", image());
            var failedInsert = proxy(PiReceiptAttachmentMapper.class, f.mapper, "insert", () -> { throw new IllegalStateException("injected insert failure"); });
            var failedAudit = proxy(OperationLogMapper.class, f.core.logs, "insert", () -> { throw new IllegalStateException("injected audit failure"); });
            for (var service : List.of(f.service(storage(), failedInsert, f.core.logs), f.service(storage(), f.mapper, failedAudit))) {
                assertThatThrownBy(() -> f.finance(() -> upload(service, piId, paymentId, "failed", png))).isInstanceOf(IllegalStateException.class);
                assertThat(f.count()).isZero(); assertThat(f.auditCount()).isZero(); assertFiles(0);
            }
            var service = f.service(storage(), f.mapper, f.core.logs);
            assertThatThrownBy(() -> f.finance(() -> {
                upload(service, piId, paymentId, "outer-failed", png);
                throw new IllegalStateException("injected later transaction failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.count()).isZero(); assertThat(f.auditCount()).isZero(); assertFiles(0);
        }
    }

    @Test void partialStorageWriteIsRemovedWhenWriteFails() throws Exception {
        try (var f = new Fixture()) {
            long piId = f.core.confirmed(), paymentId = f.receipt(piId);
            var broken = new ProductionFileStore(temp.toString(), "local", "", "", "", "") {
                @Override public void write(String key, InputStream stream, long size, String type) throws IOException {
                    super.write(key, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, type);
                    throw new IOException("injected partial object failure");
                }
            };
            var service = f.service(broken, f.mapper, f.core.logs);
            assertThatThrownBy(() -> f.finance(() -> upload(service, piId, paymentId, "partial", file("proof.png", "image/png", image())))).hasRootCauseInstanceOf(IOException.class);
            assertThat(f.count()).isZero(); assertThat(f.auditCount()).isZero(); assertFiles(0);
        }
    }

    @Test void simultaneousSameRequestReturnsOneAttachmentAndOneAudit() throws Exception {
        try (var f = new Fixture(); var executor = Executors.newFixedThreadPool(2)) {
            long piId = f.core.confirmed(), paymentId = f.receipt(piId);
            var started = new CountDownLatch(2);
            // Force both transactions to establish their repeatable-read snapshot before locking PI.
            var concurrent = (PiReceiptAttachmentMapper) Proxy.newProxyInstance(PiReceiptAttachmentMapper.class.getClassLoader(), new Class<?>[]{PiReceiptAttachmentMapper.class}, (proxy, method, args) -> {
                var result = invoke(method, f.mapper, args);
                if (method.getName().equals("selectReceipt")) { started.countDown(); await(started); }
                return result;
            });
            var target = f.service(storage(), concurrent, f.core.logs);
            var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.core.source),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service = (PiReceiptAttachmentService)proxy.getProxy();
            var png = file("proof.png", "image/png", image());
            Callable<PiReceiptAttachmentView> upload = () -> {
                PiServiceTest.login("PERM_PI_READ","PERM_PI_PAYMENT_CONFIRM");
                try { return upload(service,piId,paymentId,"concurrent",png); } finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(upload); var second = executor.submit(upload);
            assertThat(first.get(15, TimeUnit.SECONDS).id()).isEqualTo(second.get(15, TimeUnit.SECONDS).id());
            assertThat(f.count()).isEqualTo(1); assertThat(f.auditCount()).isEqualTo(1); assertFiles(1);
        }
    }

    @Test void waitingUploadRechecksConfirmedReceiptAfterLock() throws Exception {
        try (var f = new Fixture(); var executor = Executors.newFixedThreadPool(2)) {
            long piId = f.core.confirmed(), paymentId = f.receipt(piId);
            var locked = new CountDownLatch(1); var ready = new CountDownLatch(1);
            var pending = proxy(PiReceiptAttachmentMapper.class, f.mapper, "lockPi", () -> { ready.countDown(); return f.mapper.lockPi(piId); });
            var service = f.service(storage(), pending, f.core.logs);
            var png = file("proof.png", "image/png", image());
            var changer = executor.submit(() -> f.finance(() -> {
                f.mapper.lockPi(piId); f.mapper.lockReceipt(piId, paymentId); locked.countDown(); await(ready);
                new org.springframework.jdbc.core.JdbcTemplate(f.core.source).update("UPDATE " + f.core.prefix + "pi_payment SET status='VOIDED' WHERE id=?", paymentId);
                return null;
            }));
            await(locked);
            var uploader = executor.submit(() -> f.finance(() -> upload(service, piId, paymentId, "waiting", png)));
            changer.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> uploader.get(15, TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.bike.server.common.BusinessException.class)
                    .rootCause().extracting("code").isEqualTo("INVALID_STATUS_TRANSITION");
            assertThat(f.count()).isZero(); assertThat(f.auditCount()).isZero(); assertFiles(0);
        }
    }

    private ProductionFileStore storage() { return new ProductionFileStore(temp.toString(), "local", "", "", "", ""); }
    private void assertFiles(long expected) throws IOException { try (var files = Files.list(temp)) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(expected); } }
    private static PiReceiptAttachmentView upload(PiReceiptAttachmentService service, long piId, long paymentId, String request, MultipartFile file) {
        try { return service.upload(piId, paymentId, request, file); } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, T target, String selectedMethod, Supplier<Object> replacement) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> method.getName().equals(selectedMethod) ? replacement.get() : invoke(method, target, args));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent test timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    static final class Fixture implements AutoCloseable {
        final PiMysqlTest.Fixture core = new PiMysqlTest.Fixture();
        final PiReceiptAttachmentMapper mapper;
        Fixture() throws Exception {
            try {
                var config = new Configuration(new Environment("pi-receipt", new SpringManagedTransactionFactory(), core.source));
                config.setMapUnderscoreToCamelCase(true);
                try (var input = Resources.getResourceAsStream("mapper/PiReceiptAttachmentMapper.xml")) {
                    var xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", core.prefix);
                    new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), config, "PiReceiptAttachmentMapper", config.getSqlFragments()).parse();
                }
                mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(PiReceiptAttachmentMapper.class);
                try (var input = Resources.getResourceAsStream("db/migration/V27__pi_receipt_attachments.sql")) {
                    var ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", core.prefix);
                    for (String sql : ddl.split(";")) if (!sql.isBlank()) core.create(sql.trim());
                }
                try (var input = Resources.getResourceAsStream("db/migration/V47__production_attachment_integrity.sql")) {
                    var ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", core.prefix);
                    for (String sql : ddl.split(";")) if (sql.contains("ALTER TABLE " + core.prefix + "pi_payment_attachment")) core.execute(sql.trim());
                }
            } catch (Exception error) { core.close(); throw error; }
        }
        PiReceiptAttachmentService service(ProductionFileStore files, PiReceiptAttachmentMapper mapper, OperationLogMapper logs) {
            var validator = new PiReceiptFileValidator(new com.bike.server.common.BusinessUploadValidator());
            return com.bike.server.common.CustomerScopeTestSupport.isolated(new PiReceiptAttachmentService(mapper, files, validator, logs, core.json));
        }
        long receipt(long piId) { return core.transaction(() -> core.service.recordPayment(piId, PiServiceTest.payment("receipt-" + UUID.randomUUID(), "30.00"))).getPayments().getFirst().id(); }
        <T> T finance(Supplier<T> action) {
            PiServiceTest.login("PERM_PI_READ", "PERM_PI_PAYMENT_CONFIRM");
            try { return core.tx.execute(status -> action.get()); } finally { SecurityContextHolder.clearContext(); }
        }
        long count() throws Exception { return core.scalar("SELECT COUNT(*) FROM " + core.prefix + "pi_payment_attachment"); }
        long auditCount() throws Exception { return core.scalar("SELECT COUNT(*) FROM " + core.prefix + "operation_log WHERE action='上传 PI 收款凭证'"); }
        String state(long piId) { return new org.springframework.jdbc.core.JdbcTemplate(core.source).queryForList("SELECT p.status,p.payment_status,p.release_status,p.total_amount,receipt.amount,receipt.status AS receipt_status FROM " + core.prefix + "pi p JOIN " + core.prefix + "pi_payment receipt ON receipt.pi_id=p.id WHERE p.id=?", piId).toString(); }
        @Override public void close() throws Exception { core.close(); }
    }
}
