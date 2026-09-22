package com.bike.server.aftersales;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class AfterSalesReturnMysqlTest {
    static AfterSalesReturnRequest action(long revision,String action) {
        return new AfterSalesReturnRequest(revision,action,"Return warehouse","Recipient","1234567","Test address","10000","DHL","RETURN-TEST","Motor bearing worn","Tested and disposition approved");
    }
    private static long approvedCase(AfterSalesMysqlTest.Fixture f) {
        long id=f.submittedCase();
        f.admin(()->f.service.review(id,new AfterSalesReviewRequest("START",null)));
        var plan=f.admin(()->f.service.saveSolution(id,AfterSalesPlanMysqlTest.plan(null,"motor",2,true))).getSolutionVersion();
        f.admin(()->f.service.confirmSolution(id,plan.id()));
        f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,plan.id())));
        return id;
    }
    private static AfterSalesShipmentView replacement(AfterSalesMysqlTest.Fixture f,long id) {
        return f.admin(()->f.service.addShipment(id,new AfterSalesShipmentRequest("motor",1,"DHL","OUTGOING-TEST",true,f.service.get(id).getSolutionVersion().parts().getFirst().id(),AfterSalesPlanMysqlTest.shipping())));
    }

    @Test void completeReturnKeepsIndependentPartsAndBlocksPrematureCaseArchive() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var service=new AfterSalesReturnService(f.mapper);
            long id=approvedCase(f); long first=replacement(f,id).getId(),second=replacement(f,id).getId();
            assertThat(f.agent(()->service.list(id))).extracting(AfterSalesReturnView::status).containsExactly("REQUIRED","REQUIRED");
            f.admin(()->f.service.resolve(id));
            assertCode("AFTER_SALES_RETURN_PENDING",()->f.agent(()->f.service.close(id)));
            long revision=1;
            for(String action:List.of("REQUEST","SEND","RECEIVE","INSPECT","SCRAP","COMPLETE")) {
                var request=action(revision++,action);
                var updated=action.equals("SEND")?f.agent(()->service.act(id,first,request)):f.admin(()->service.act(id,first,request));
                assertThat(updated.revision()).isEqualTo(revision);
            }
            var finished=f.agent(()->service.list(id)).getFirst();
            assertThat(finished.status()).isEqualTo("COMPLETED"); assertThat(finished.receivedAt()).isNotNull();
            assertThat(finished.inspectionResult()).isEqualTo("Motor bearing worn");assertThat(finished.completedAt()).isNotNull();
            assertThat(f.mapper.selectShipmentById(first).getOldPartReturnReceivedAt()).isNotNull();
            assertCode("AFTER_SALES_RETURN_PENDING",()->f.agent(()->f.service.close(id)));
            f.admin(()->service.act(id,second,action(1,"WAIVE")));
            assertThat(f.agent(()->f.service.close(id)).getStatus()).isEqualTo("CLOSED");
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->service.act(id,first,action(7,"REQUEST"))));
            assertThat(f.agent(()->f.service.history(id))).extracting(AfterSalesHistoryView::action)
                .contains("RETURN_REQUEST","RETURN_SEND","RETURN_RECEIVE","RETURN_INSPECT","RETURN_SCRAP","RETURN_COMPLETE","RETURN_WAIVE");
        }
    }

    @Test void companyScopeAndOperationPermissionsApplyBeforeAnyReturnWrite() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var service=new AfterSalesReturnService(f.mapper);long id=approvedCase(f),shipment=replacement(f,id).getId();
            assertCode("FORBIDDEN",()->f.as("AGENT",99L,()->service.list(id),"ROLE_WEB_AGENT","PERM_AFTER_SALES_READ"));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.list(id),"PERM_AFTER_SALES_READ"));
            assertCode("PERMISSION_DENIED",()->f.as("PDA_OPERATOR",null,()->service.list(id),"ROLE_SUPER_ADMIN"));
            for(String action:List.of("REQUEST","RECEIVE","INSPECT","SCRAP","COMPLETE","WAIVE"))
                assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->service.act(id,shipment,action(1,action)),"ROLE_WEB_AGENT","ROLE_SUPER_ADMIN"));
            assertCode("PERMISSION_DENIED",()->f.as("WEB_OPERATOR",null,()->service.act(id,shipment,action(1,"INSPECT")),"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_LOGISTICS","PERM_AFTER_SALES_SCOPE_ALL"));
            f.admin(()->service.act(id,shipment,action(1,"REQUEST")));
            assertCode("PERMISSION_DENIED",()->f.as("AGENT",8L,()->service.act(id,shipment,action(2,"SEND")),"PERM_AFTER_SALES_READ"));
            assertCode("FORBIDDEN",()->f.as("AGENT",99L,()->service.act(id,shipment,action(2,"SEND")),"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_WRITE"));
            long other=approvedCase(f);
            assertCode("AFTER_SALES_RETURN_NOT_FOUND",()->f.admin(()->service.act(other,shipment,action(2,"SEND"))));
            assertThat(f.admin(()->service.list(id)).getFirst().status()).isEqualTo("WAITING_CUSTOMER");
        }
    }

    @Test void missingAddressesTrackingAndReasonsAreRejectedWithoutStateChanges() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var service=new AfterSalesReturnService(f.mapper);long id=approvedCase(f),shipment=replacement(f,id).getId();
            for(String action:List.of("REQUEST","WAIVE")) {
                var invalid=new AfterSalesReturnRequest(1,action,null,null,null,null,null,null,null,null,null);
                assertCode("INVALID_PARAMETER",()->f.admin(()->service.act(id,shipment,invalid)));
            }
            assertCode("INVALID_STATUS_TRANSITION",()->f.agent(()->service.act(id,shipment,action(1,"SEND"))));
            f.admin(()->service.act(id,shipment,action(1,"REQUEST")));
            assertCode("INVALID_PARAMETER",()->f.agent(()->service.act(id,shipment,new AfterSalesReturnRequest(2,"SEND",null,null,null,null,null,"DHL",null,null,null))));
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->service.act(id,shipment,action(2,"RECEIVE"))));
            assertThat(f.agent(()->service.list(id)).getFirst().revision()).isEqualTo(2);
        }
    }

    @Test void concurrentCustomerSendCommitsOnceAndStaleAddressEditsCannotOverwriteTransit() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var service=new AfterSalesReturnService(f.mapper);long id=approvedCase(f),shipment=replacement(f,id).getId();
            f.admin(()->service.act(id,shipment,action(1,"REQUEST")));
            var start=new CountDownLatch(1);
            Callable<String> send=()->{start.await();try{f.agent(()->service.act(id,shipment,action(2,"SEND")));return "SUCCESS";}catch(com.bike.server.common.BusinessException e){return e.getCode();}};
            var first=pool.submit(send);var second=pool.submit(send);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","AFTER_SALES_RETURN_CONFLICT");
            assertCode("AFTER_SALES_RETURN_CONFLICT",()->f.admin(()->service.act(id,shipment,action(2,"REQUEST"))));
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->service.act(id,shipment,action(3,"REQUEST"))));
            assertThat(f.agent(()->f.service.history(id)).stream().filter(h->h.action().equals("RETURN_SEND")).count()).isEqualTo(1);
        }
    }

    @Test void auditFailureRollsBackReturnAndLegacyReceivedTimestampTogether() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var service=new AfterSalesReturnService(f.mapper);long id=approvedCase(f),shipment=replacement(f,id).getId();
            f.admin(()->service.act(id,shipment,action(1,"REQUEST")));f.agent(()->service.act(id,shipment,action(2,"SEND")));
            var brokenMapper=(AfterSalesMapper)Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertAudit"))return 0;
                try{return method.invoke(f.mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
            var broken=new AfterSalesReturnService(brokenMapper);
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.act(id,shipment,action(3,"RECEIVE"))));
            var record=f.agent(()->service.list(id)).getFirst();assertThat(record.status()).isEqualTo("IN_TRANSIT");assertThat(record.revision()).isEqualTo(3);
            assertThat(f.mapper.selectShipmentById(shipment).getOldPartReturnReceivedAt()).isNull();
        }
    }
}
