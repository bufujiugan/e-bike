package com.bike.server.aftersales;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;
import static com.bike.server.aftersales.AfterSalesPlanMysqlTest.*;
import static com.bike.server.aftersales.AfterSalesWarehouseMysqlTest.warehouse;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class AfterSalesLogisticsMysqlTest {
    static AfterSalesShipmentView prepared(AfterSalesMysqlTest.Fixture f,boolean returns) {
        long id=reviewCase(f);var p=approve(f,id,plan(null,"Motor",2,returns));
        return warehouse(f,()->f.service.addShipment(id,shipment(p,2)));
    }
    static AfterSalesLogisticsRequest request(AfterSalesShipmentView row,String action,String status,String note) {
        return new AfterSalesLogisticsRequest(row.getRevision(),action,null,"DHL","CORRECTED",status,note,LocalDate.now().plusDays(7));
    }

    @Test void immediateDispatchKeepsExpectedArrivalAndRejectsIncompleteShippingAtomically() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var p=approve(f,id,plan(null,"Motor",2,false));
            assertCode("INVALID_PARAMETER",()->warehouse(f,()->f.service.addShipment(id,new AfterSalesShipmentRequest("Motor",1,"DHL","MISSING",false,p.parts().getFirst().id()))));
            assertThat(f.mapper.selectShipments(id)).isEmpty();
            var sent=warehouse(f,()->f.service.addShipment(id,new AfterSalesShipmentRequest("Motor",1,"DHL","DIRECT",false,p.parts().getFirst().id(),shipping())));
            assertThat(sent.getExpectedArrivalDate()).isEqualTo(shipping().expectedArrivalDate());assertThat(sent.getLogisticsStatus()).isEqualTo("IN_TRANSIT");
        }
    }

    @Test void dispatchRequiresCompleteSnapshotAndRejectsStaleRecipientEdits() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var row=prepared(f,false);long id=row.getCaseId();var logistics=new AfterSalesLogisticsService(f.mapper);
            assertCode("INVALID_PARAMETER",()->warehouse(f,()->f.service.dispatchShipment(id,row.getId(),new AfterSalesDispatchRequest("DHL","TRACK",null,1))));
            assertThat(f.mapper.selectShipmentById(row.getId()).getStatus()).isEqualTo("PLANNED");
            var updated=warehouse(f,()->logistics.act(id,row.getId(),new AfterSalesLogisticsRequest(1,"SAVE",shipping(),null,null,null,"Confirm recipient")));
            assertThat(updated.getRevision()).isEqualTo(2);assertThat(updated.getRecipient()).isEqualTo("Test recipient");
            assertCode("AFTER_SALES_SHIPMENT_CONFLICT",()->warehouse(f,()->f.service.dispatchShipment(id,row.getId(),new AfterSalesDispatchRequest("DHL","STALE",shipping(),1))));
            var sent=warehouse(f,()->f.service.dispatchShipment(id,row.getId(),new AfterSalesDispatchRequest("DHL","TRACK",null,2)));
            assertThat(sent.getStatus()).isEqualTo("SHIPPED");assertThat(sent.getLogisticsStatus()).isEqualTo("IN_TRANSIT");assertThat(sent.getPartCode()).isEqualTo("MOTOR");
            f.sql("UPDATE "+f.prefix+"after_sales_case_contact SET contact_name='New recipient',address_line='New address' WHERE case_id="+id);
            var retained=f.agent(()->f.service.shipments(id)).getFirst();assertThat(retained.getRecipient()).isEqualTo("Test recipient");assertThat(retained.getAddressLine()).isEqualTo("Isolated test address");
            assertCode("INVALID_STATUS_TRANSITION",()->warehouse(f,()->logistics.act(id,row.getId(),new AfterSalesLogisticsRequest(sent.getRevision(),"SAVE",shipping(),null,null,null,"edit shipped"))));
        }
    }

    @Test void cancellationReleasesApprovedQuantityAndCancelsUnstartedReturnWithoutDeletingHistory() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var row=prepared(f,true);long id=row.getCaseId();var logistics=new AfterSalesLogisticsService(f.mapper);
            var cancel=warehouse(f,()->logistics.act(id,row.getId(),request(row,"CANCEL",null,"Wrong dispatch plan")));
            assertThat(cancel.getStatus()).isEqualTo("CANCELLED");assertThat(f.mapper.selectReturn(row.getId()).status()).isEqualTo("CANCELLED");
            assertThat(f.mapper.countPendingReturns(id)).isZero();assertThat(f.mapper.countPendingShipments(id)).isZero();
            assertThat(f.admin(()->f.service.get(id)).getSolutionVersion().parts().getFirst().allocatedQty()).isZero();
            assertCode("AFTER_SALES_SHIPMENT_PENDING",()->f.admin(()->f.service.resolve(id)));
            var plan=f.admin(()->f.service.get(id)).getSolutionVersion();warehouse(f,()->f.service.addShipment(id,shipment(plan,2)));
            assertThat(f.mapper.selectShipments(id)).hasSize(2);
            assertThat(f.admin(()->f.service.history(id))).extracting(AfterSalesHistoryView::action).contains("SHIPMENT_CANCEL");
            assertCode("AFTER_SALES_SHIPMENT_CONFLICT",()->warehouse(f,()->logistics.act(id,row.getId(),request(row,"CANCEL",null,"repeat"))));
        }
    }

    @Test void startedReturnCannotBeOrphanedByCancellation() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var row=prepared(f,true);long id=row.getCaseId();var logistics=new AfterSalesLogisticsService(f.mapper);
            f.sql("UPDATE "+f.prefix+"after_sales_return SET status='WAITING_CUSTOMER' WHERE shipment_id="+row.getId());
            assertCode("AFTER_SALES_RETURN_STARTED",()->warehouse(f,()->logistics.act(id,row.getId(),request(row,"CANCEL",null,"cancel"))));
            assertThat(f.mapper.selectShipmentById(row.getId()).getStatus()).isEqualTo("PLANNED");
            f.sql("UPDATE "+f.prefix+"after_sales_return SET inspection_result='Private technical findings',disposition_note='Private cost responsibility' WHERE shipment_id="+row.getId());
            var returned=warehouse(f,()->new AfterSalesReturnService(f.mapper).list(id)).getFirst();
            assertThat(returned.inspectionResult()).isNull();assertThat(returned.dispositionNote()).isNull();
        }
    }

    @Test void trackingAndCorrectionPreserveDispatchFactsAndRespectScopeAndTerminalState() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var row=prepared(f,false);long id=row.getCaseId();var logistics=new AfterSalesLogisticsService(f.mapper);
            var sent=warehouse(f,()->f.service.dispatchShipment(id,row.getId(),new AfterSalesDispatchRequest("DHL","ORIGINAL",shipping(),1)));
            assertCode("FORBIDDEN",()->f.agent(()->logistics.act(id,row.getId(),request(sent,"TRACK","DELIVERED","forged"))));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->logistics.act(id,row.getId(),request(sent,"TRACK","DELIVERED","wrong scope")),"PERM_AFTER_SALES_LOGISTICS"));
            var exception=warehouse(f,()->logistics.act(id,row.getId(),request(sent,"TRACK","EXCEPTION","Customs delay")));
            var corrected=warehouse(f,()->logistics.act(id,row.getId(),request(exception,"CORRECT",null,"Carrier corrected tracking number")));
            assertThat(corrected.getTrackingNo()).isEqualTo("CORRECTED");assertThat(corrected.getShippedAt()).isEqualTo(sent.getShippedAt());assertThat(corrected.getAddressLine()).isEqualTo(sent.getAddressLine());
            var delivered=warehouse(f,()->logistics.act(id,row.getId(),request(corrected,"TRACK","DELIVERED","Recipient confirmed")));
            assertThat(delivered.getDeliveredAt()).isNotNull();
            assertCode("INVALID_STATUS_TRANSITION",()->warehouse(f,()->logistics.act(id,row.getId(),request(delivered,"TRACK","IN_TRANSIT","undo"))));
            assertCode("INVALID_STATUS_TRANSITION",()->warehouse(f,()->logistics.act(id,row.getId(),request(delivered,"CANCEL",null,"cancel shipped"))));
            f.admin(()->f.service.resolve(id));f.agent(()->f.service.close(id));
            assertCode("INVALID_STATUS_TRANSITION",()->warehouse(f,()->logistics.act(id,row.getId(),request(delivered,"CORRECT",null,"closed"))));
        }
    }

    @Test void invalidDateOrAuditFailureRollsBackShippingChanges() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var row=prepared(f,false);long id=row.getCaseId();var d=shipping();
            var future=new AfterSalesShippingDetails(d.warehouseName(),d.recipient(),d.phone(),d.countryCode(),d.region(),d.city(),d.addressLine(),d.postalCode(),d.expectedArrivalDate(),LocalDateTime.now().plusDays(1));
            assertCode("INVALID_PARAMETER",()->warehouse(f,()->f.service.dispatchShipment(id,row.getId(),new AfterSalesDispatchRequest("DHL","FUTURE",future,1))));
            var broken=new AfterSalesLogisticsService((AfterSalesMapper)Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertAudit"))return 0;try{return method.invoke(f.mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
            }));
            assertCode("AFTER_SALES_AUDIT_FAILED",()->warehouse(f,()->broken.act(id,row.getId(),new AfterSalesLogisticsRequest(1,"SAVE",d,null,null,null,"new warehouse"))));
            assertThat(f.mapper.selectShipmentById(row.getId()).getWarehouseName()).isNull();assertThat(f.mapper.selectShipmentById(row.getId()).getRevision()).isEqualTo(1);
            assertCode("AFTER_SALES_AUDIT_FAILED",()->warehouse(f,()->broken.act(id,row.getId(),request(row,"CANCEL",null,"cancel"))));
            assertThat(f.mapper.selectShipmentById(row.getId()).getStatus()).isEqualTo("PLANNED");
        }
    }

    @Test void concurrentEditsOfSameShippingRevisionHaveExactlyOneWinner() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var row=prepared(f,false);var logistics=new AfterSalesLogisticsService(f.mapper);var start=new CountDownLatch(1);
            Callable<String> update=()->{start.await();try{warehouse(f,()->logistics.act(row.getCaseId(),row.getId(),new AfterSalesLogisticsRequest(1,"SAVE",shipping(),null,null,null,"concurrent")));return "SUCCESS";}catch(com.bike.server.common.BusinessException e){return e.getCode();}};
            var a=pool.submit(update);var b=pool.submit(update);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","AFTER_SALES_SHIPMENT_CONFLICT");
        }
    }
}
