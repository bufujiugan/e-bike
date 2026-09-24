package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.lock.SkuLockRequest;
import com.bike.server.order.SalesOrderCreateRequest;
import com.bike.server.pi.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiReportMysqlTest {
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static PiReportService service(CustomerScopeMysqlTest.Fixture t){return new PiReportService(t.reports,t.f.pis,t.audit,t.access);}
    static long count(PiReportService service,String key){return service.summary(null).get(key);}
    static void finance(){CustomerScopeMysqlTest.login(1,"WEB_OPERATOR","PERM_PI_READ","PERM_PI_EXPORT","PERM_PI_PAYMENT_CONFIRM");}
    static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}

    @Test void queuesFollowPublishedRevisionReceiptsAndActualShippingWithoutDoubleCounting()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var f=t.f;var reports=service(t);var original=PiPaymentCorrectionMysqlTest.paid(f);
            t.as(2,()->f.pis.create(CustomerScopeMysqlTest.request(2)));
            finance();assertThat(count(reports,"AWAITING_PAYMENT")).isZero();assertThat(count(reports,"AWAITING_SHIPMENT")).isEqualTo(1);
            assertThat(count(reports,"MISSING_PI")).isZero();
            var revision=PiRevisionLifecycleMysqlTest.revisions(f);
            var draft=f.tx(()->revision.create(original.getId(),PiRevisionLifecycleMysqlTest.request("report-revision")));
            finance();assertThat(count(reports,"MISSING_PI")).isEqualTo(1);
            f.tx(()->f.pis.update(draft.getId(),PiRevisionLifecycleMysqlTest.edit(draft,"CNY",1)));
            f.tx(()->f.pis.confirm(draft.getId()));
            finance();assertThat(count(reports,"MISSING_PI")).isZero();assertThat(count(reports,"AWAITING_PAYMENT")).isEqualTo(1);
            var waiting=reports.queue("AWAITING_SHIPMENT",null,1,20);
            assertThat(waiting.total()).isEqualTo(1);assertThat(number(waiting.records().getFirst(),"piId")).isEqualTo(draft.getId());
            assertThat(number(waiting.records().getFirst(),"orderedQty")).isEqualTo(2);
            assertThat(new BigDecimal((String)waiting.records().getFirst().get("paidAmount"))).isEqualByComparingTo("200");
            f.tx(()->f.pis.release(draft.getId(),new PiReleaseRequest("核对修订门槛")));
            long red=original.getFulfillment().stream().filter(i->i.skuId()==10).findFirst().orElseThrow().orderId();
            long green=original.getFulfillment().stream().filter(i->i.skuId()==12).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100"))));
            finance();assertThat(count(reports,"AWAITING_SHIPMENT")).isZero();assertThat(count(reports,"PARTIAL_SHIPMENT")).isEqualTo(1);
            var partial=reports.queue("PARTIAL_SHIPMENT",null,1,20).records().getFirst();
            assertThat(number(partial,"shippedQty")).isEqualTo(1);assertThat(number(partial,"remainingQty")).isEqualTo(1);
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,green,List.of("QR120"))));
            finance();assertThat(count(reports,"PARTIAL_SHIPMENT")).isZero();
            f.tx(()->f.pis.reversePayment(draft.getId(),original.getPayments().getFirst().id(),new PiPaymentReverseRequest("report-reverse",1L,"核对错误登记")));
            finance();assertThat(new BigDecimal((String)reports.queue("AWAITING_PAYMENT",null,1,20).records().getFirst().get("paidAmount"))).isEqualByComparingTo("0");
            CustomerScopeMysqlTest.login(2,"WEB_OPERATOR","PERM_PI_READ","PERM_PI_PAYMENT_CONFIRM");
            assertThat(count(reports,"AWAITING_PAYMENT")).isZero();assertThat(count(reports,"MISSING_PI")).isEqualTo(1);
        }
    }

    @Test void missingPiPaginationAndExcelExportIncludeEveryAuthorizedRow()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var reports=service(t);PiView last=null;
            for(int i=0;i<105;i++)last=t.as(1,()->t.f.pis.create(CustomerScopeMysqlTest.request(1)));
            for(int i=0;i<3;i++)t.as(2,()->t.f.pis.create(CustomerScopeMysqlTest.request(2)));
            t.f.exec("UPDATE @pi SET document_snapshot=JSON_SET(COALESCE(document_snapshot,JSON_OBJECT()),'$.businessOwner','=2+3') WHERE id="+last.getId());
            t.as(1,()->t.orders.create(new SalesOrderCreateRequest(1L,null,4L,1,null,null)));
            t.as(1,()->{
                assertThat(count(reports,"MISSING_PI")).isEqualTo(106);
                assertThat(reports.queue("MISSING_PI",null,1,100).records()).hasSize(100);
                assertThat(reports.queue("MISSING_PI",null,2,100).records()).hasSize(6);
                assertThat(reports.queue("MISSING_PI","客户2",1,100).total()).isZero();return null;
            });
            CustomerScopeMysqlTest.login(1,"WEB_OPERATOR","PERM_PI_READ","PERM_PI_EXPORT");
            assertThat(count(reports,"MISSING_PI")).isEqualTo(105);
            assertThat(reports.summary(null)).containsEntry("AWAITING_PAYMENT",null).containsEntry("EXPIRING_LOCKS",null);
            assertThatThrownBy(()->reports.queue("AWAITING_PAYMENT",null,1,20)).isInstanceOf(BusinessException.class);
            assertThat(reports.queue("MISSING_PI",null,1,20).records()).allSatisfy(row->assertThat(row).doesNotContainKey("paidAmount"));
            byte[] bytes=t.f.transaction.execute(s->reports.export(null,"DRAFT"));
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(105);assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short)14);
                Set<String> ids=new HashSet<>();boolean formulaLike=false;
                for(int row=1;row<=105;row++) {
                    var r=sheet.getRow(row);ids.add(r.getCell(0).getStringCellValue());
                    assertThat(r.getCell(3).getStringCellValue()).isEqualTo("客户1");
                    assertThat(r.getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(r.getCell(6).getNumericCellValue()).isEqualTo(10);
                    if(r.getCell(8).getCellType()==CellType.STRING&&r.getCell(8).getStringCellValue().equals("=2+3"))formulaLike=true;
                    for(var cell:r)assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                }
                assertThat(ids).hasSize(105);assertThat(formulaLike).isTrue();
            }
            finance();try(var book=WorkbookFactory.create(new ByteArrayInputStream(t.f.transaction.execute(s->reports.export(lastPiKeyword(t),"DRAFT"))))){assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short)16);}
            t.grant(1,1,List.of());finance();
            assertThat(count(reports,"MISSING_PI")).isZero();
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(t.f.transaction.execute(s->reports.export(null,null))))){assertThat(book.getSheetAt(0).getLastRowNum()).isZero();}
        }
    }
    private static String lastPiKeyword(CustomerScopeMysqlTest.Fixture t){return t.f.pis.list(null,null,1,1).records().getFirst().getPiNo();}

    @Test void expiringLocksUseRemainingQuantityTimeWindowAndScope()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var reports=service(t);
            var own=t.as(1,()->t.f.locks.create(new SkuLockRequest("report-own",1L,null,null,null,List.of(new SkuLockRequest.Item(11,1))))).getFirst();
            var other=t.as(2,()->t.f.locks.create(new SkuLockRequest("report-other",2L,null,null,null,List.of(new SkuLockRequest.Item(10,1))))).getFirst();
            t.f.exec("UPDATE @inventory_lock SET expire_time=DATE_ADD(NOW(3),INTERVAL 23 HOUR)");
            t.as(1,()->{assertThat(count(reports,"EXPIRING_LOCKS")).isEqualTo(1);assertThat(number(reports.queue("EXPIRING_LOCKS",null,1,20).records().getFirst(),"id")).isEqualTo(own.getId());return null;});
            for(String update:List.of("expire_time=DATE_ADD(NOW(3),INTERVAL 25 HOUR)","expire_time=DATE_SUB(NOW(3),INTERVAL 1 SECOND)","expire_time=DATE_ADD(NOW(3),INTERVAL 1 HOUR),released_qty=lock_qty")) {
                t.f.exec("UPDATE @inventory_lock SET "+update+" WHERE id="+own.getId());
                t.as(1,()->{assertThat(count(reports,"EXPIRING_LOCKS")).isZero();return null;});
            }
            t.as(2,()->{assertThat(number(reports.queue("EXPIRING_LOCKS",null,1,20).records().getFirst(),"id")).isEqualTo(other.getId());return null;});
        }
    }

    @Test void searchFindsSkuOrderStockOutAndHistoricalLogisticsWithSameScopedCount()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var f=t.f;var pi=PiPaymentCorrectionMysqlTest.paid(f);
            long red=pi.getFulfillment().stream().filter(i->i.skuId()==10).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100"))));
            f.tx(()->f.logistics.save(pi.getId(),null,new PiLogisticsRequest("report-batch","承运商","report-tracking","PLANNED",null,null,"report-logistics",null)));
            var revisions=PiRevisionLifecycleMysqlTest.revisions(f);var draft=f.tx(()->revisions.create(pi.getId(),PiRevisionLifecycleMysqlTest.request("report-search")));
            f.tx(()->f.pis.confirm(draft.getId()));
            String out=f.jdbc.queryForObject("SELECT stock_out_no FROM "+f.prefix+"stock_out_record WHERE order_id=?",String.class,red);
            String batch=f.dispatchMapper.batches(pi.getId()).getFirst().batchNo();
            for(String keyword:List.of("report-tracking","report-batch",out,batch,pi.getFulfillment().getFirst().orderNo(),pi.getItems().getFirst().getSkuCode(),pi.getCustomerCode())) {
                t.as(1,()->{var result=f.pis.list(keyword,null,1,1);assertThat(result.total()).as(keyword).isEqualTo(2);assertThat(result.records()).hasSize(1);
                    assertThat(f.pis.list(keyword,"CONFIRMED",1,20).total()).isEqualTo(1);return null;});
                t.as(2,()->{assertThat(f.pis.list(keyword,null,1,100).total()).isZero();return null;});
            }
            // Cancelled remainder remains historical ordered quantity but no longer belongs in a shipping queue.
            f.tx(()->t.orders.cancel(pi.getFulfillment().stream().filter(i->i.skuId()==12).findFirst().orElseThrow().orderId(),"取消未发子单"));
            finance();assertThat(count(service(t),"PARTIAL_SHIPMENT")).isZero();
        }
    }

    @Test void closingPartiallyShippedRemainderRemovesOnlyThatOutstandingQuantity()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var f=t.f;var reports=service(t);var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(103,10);
            var revision=PiQuantityRevisionMysqlTest.revise(f,original,"report-close",2);
            var published=f.tx(()->f.pis.confirm(revision.getId()));
            f.tx(()->f.pis.release(published.getId(),new PiReleaseRequest("核对关闭前发货")));
            long red=published.getFulfillment().stream().filter(i->i.skuId()==10).findFirst().orElseThrow().orderId();
            long green=published.getFulfillment().stream().filter(i->i.skuId()==12).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100"))));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,green,List.of("QR120"))));
            finance();assertThat(count(reports,"PARTIAL_SHIPMENT")).isEqualTo(1);
            f.tx(()->f.renew.closeRemainder(red,"客户不再收取剩余一台"));
            finance();assertThat(count(reports,"PARTIAL_SHIPMENT")).isZero();assertThat(count(reports,"AWAITING_SHIPMENT")).isZero();
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(f.transaction.execute(s->reports.export(published.getPiNo(),"RELEASED"))))) {
                var row=book.getSheetAt(0).getRow(1);assertThat(row.getCell(11).getNumericCellValue()).isEqualTo(2);
                assertThat(row.getCell(12).getNumericCellValue()).isZero();assertThat(row.getCell(13).getNumericCellValue()).isEqualTo(1);
            }
            assertThatThrownBy(()->reports.queue("UNKNOWN",null,1,20)).hasMessageContaining("不存在");
            assertThatThrownBy(()->reports.summary("x".repeat(201))).hasMessageContaining("200");
        }
    }

    @Test void exportRequiresBothPermissionsAuditingAndAgentOwnership()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var reports=service(t);var pi=PiPaymentCorrectionMysqlTest.paid(t.f);
            CustomerScopeMysqlTest.login(1,"WEB_OPERATOR","PERM_PI_EXPORT");assertThatThrownBy(()->reports.export(null,null)).isInstanceOf(BusinessException.class);
            CustomerScopeMysqlTest.login(1,"WEB_OPERATOR","PERM_PI_READ");assertThatThrownBy(()->reports.export(null,null)).isInstanceOf(BusinessException.class);
            finance();when(t.audit.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->t.f.transaction.execute(s->reports.export(null,null))).hasMessageContaining("审计");
            when(t.audit.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
            t.f.exec("UPDATE @pi SET agent_id=9 WHERE id="+pi.getId());
            t.as(2,()->t.f.pis.create(CustomerScopeMysqlTest.request(2)));
            var auth=new UsernamePasswordAuthenticationToken("agent",null,List.of(new SimpleGrantedAuthority("PERM_PI_READ"),new SimpleGrantedAuthority("PERM_PI_EXPORT")));
            auth.setDetails(new UserAuthRecord(4L,"agent","","AGENT",9L,null,true));SecurityContextHolder.getContext().setAuthentication(auth);
            assertThatThrownBy(()->reports.summary(null)).isInstanceOf(BusinessException.class);
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(t.f.transaction.execute(s->reports.export(null,null))))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short)14);}
            CustomerScopeMysqlTest.login(1,"PDA_OPERATOR","ROLE_SUPER_ADMIN","PERM_PI_READ","PERM_PI_EXPORT");
            assertThatThrownBy(()->reports.export(null,null)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void highPrecisionAmountsArePreservedAsExplicitTextRatherThanRounded()throws Exception {
        try(var t=new CustomerScopeMysqlTest.Fixture()) {
            var request=CustomerScopeMysqlTest.request(1);
            var huge=new PiCreateRequest(1L,null,null,"DOMESTIC","CNY",null,null,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,null,
                    List.of(new PiItemRequest(10L,1,new BigDecimal("9999999999999999.99"),null,null)));
            var saved=t.as(1,()->t.f.pis.create(huge));finance();var reports=service(t);
            assertThat(reports.queue("MISSING_PI",saved.getPiNo(),1,20).records().getFirst().get("totalAmount")).isEqualTo("9999999999999999.99");
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(t.f.transaction.execute(s->reports.export(saved.getPiNo(),null))))) {
                var amount=book.getSheetAt(0).getRow(1).getCell(6);assertThat(amount.getCellType()).isEqualTo(CellType.STRING);
                assertThat(amount.getStringCellValue()).isEqualTo("9999999999999999.99");assertThat(amount.getCellComment().getString().getString()).contains("精度");
            }
        }
    }
}
