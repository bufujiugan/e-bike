package com.bike.server.aftersales;

import java.math.BigDecimal;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class AfterSalesPlanMysqlTest {
    static AfterSalesSolutionRequest plan(Long previous,String name,int quantity,boolean returns) {
        return new AfterSalesSolutionRequest("motor test","replace motor","confidential liability",previous,"bearing failure","install replacement","test after repair","IN_WARRANTY","CNY",null,
            List.of(new AfterSalesSolutionRequest.Part("MOTOR",name,quantity,BigDecimal.ZERO,returns)));
    }
    static AfterSalesShippingDetails shipping() {return new AfterSalesShippingDetails("Test warehouse","Test recipient","000-0000","GB","Region","City","Isolated test address","0000",java.time.LocalDate.now().plusDays(7),null);}
    static long reviewCase(AfterSalesMysqlTest.Fixture f) {long id=f.submittedCase();f.admin(()->f.service.review(id,new AfterSalesReviewRequest("START",null)));return id;}
    static AfterSalesPlanView approve(AfterSalesMysqlTest.Fixture f,long id,AfterSalesSolutionRequest draft) {
        var plan=f.admin(()->f.service.saveSolution(id,draft)).getSolutionVersion();f.admin(()->f.service.confirmSolution(id,plan.id()));
        return f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,plan.id()))).getSolutionVersion();
    }
    static AfterSalesShipmentRequest shipment(AfterSalesPlanView p,int quantity) {
        var i=p.parts().getFirst();return new AfterSalesShipmentRequest(i.partName(),quantity,null,null,i.returnRequired(),i.id());
    }

    @Test void technicalConfirmationAndVersionBoundApprovalPrecedeEveryNewPartAllocation() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var p=f.admin(()->f.service.saveSolution(id,plan(null,"Motor",2,false))).getSolutionVersion();
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,p.id()))));
            assertCode("AFTER_SALES_SOLUTION_CONFLICT",()->f.admin(()->f.service.confirmSolution(id,p.id()+99)));
            assertCode("PERMISSION_DENIED",()->f.as("WEB_OPERATOR",null,()->f.service.confirmSolution(id,p.id()),"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_REVIEW","PERM_AFTER_SALES_SCOPE_ALL"));
            f.admin(()->f.service.confirmSolution(id,p.id()));
            assertCode("AFTER_SALES_SOLUTION_CONFLICT",()->f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null))));
            var approved=f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,p.id())));
            assertThat(approved.getWarrantyStatus()).isEqualTo("IN_WARRANTY");assertThat(approved.getSolutionVersion().approvedAt()).isNotNull();
            assertCode("AFTER_SALES_APPROVED_PART_REQUIRED",()->f.admin(()->f.service.addShipment(id,new AfterSalesShipmentRequest("Motor",1,null,null,false))));
            assertCode("INVALID_PARAMETER",()->f.admin(()->f.service.addShipment(id,new AfterSalesShipmentRequest("Other part",1,null,null,false,p.parts().getFirst().id()))));
            f.admin(()->f.service.addShipment(id,shipment(p,1)));
            assertCode("AFTER_SALES_APPROVED_QUANTITY_EXCEEDED",()->f.admin(()->f.service.addShipment(id,shipment(p,2))));
            assertThat(f.count("after_sales_shipment")).isEqualTo(1);
            var history=f.agent(()->f.service.solutionVersions(id));
            assertThat(history.getFirst().document().internalNote()).isNull();assertThat(history.getFirst().parts().getFirst().allocatedQty()).isEqualTo(1);
            assertThat(f.admin(()->f.service.solutionVersions(id)).getFirst().document().internalNote()).isEqualTo("confidential liability");
        }
    }

    @Test void parallelWarehouseRequestsCannotExceedApprovedQuantity() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            long id=reviewCase(f);var plan=approve(f,id,plan(null,"Motor",1,false));var start=new CountDownLatch(1);
            Callable<String> allocate=()->{start.await();try{f.admin(()->f.service.addShipment(id,shipment(plan,1)));return "SUCCESS";}catch(com.bike.server.common.BusinessException e){return e.getCode();}};
            var a=pool.submit(allocate);var b=pool.submit(allocate);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","AFTER_SALES_APPROVED_QUANTITY_EXCEEDED");
            assertThat(f.count("after_sales_shipment")).isEqualTo(1);
        }
    }

    @Test void revisionsKeepPriorExecutionLinksAndCountAllocationsAcrossVersions() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var first=approve(f,id,plan(null,"Motor",2,false));
            var allocated=f.admin(()->f.service.addShipment(id,shipment(first,1)));
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REVISE","更新维修说明",first.id())));
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REJECT","不再处理"))));
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->f.service.dispatchShipment(id,allocated.getId(),new AfterSalesDispatchRequest("DHL","P1",AfterSalesPlanMysqlTest.shipping(),1))));
            var second=approve(f,id,plan(first.id(),"Motor",2,false));
            assertThat(second.revision()).isEqualTo(2);assertThat(second.parts().getFirst().allocatedQty()).isEqualTo(1);
            assertCode("AFTER_SALES_APPROVED_PART_REQUIRED",()->f.admin(()->f.service.addShipment(id,shipment(first,1))));
            f.admin(()->f.service.dispatchShipment(id,allocated.getId(),new AfterSalesDispatchRequest("DHL","P1",AfterSalesPlanMysqlTest.shipping(),1)));
            assertCode("AFTER_SALES_SHIPMENT_PENDING",()->f.admin(()->f.service.resolve(id)));
            var next=f.admin(()->f.service.addShipment(id,shipment(second,1)));
            f.admin(()->f.service.dispatchShipment(id,next.getId(),new AfterSalesDispatchRequest("DHL","P2",AfterSalesPlanMysqlTest.shipping(),1)));
            assertThat(f.admin(()->f.service.resolve(id)).getStatus()).isEqualTo("RESOLVED");
            assertThat(f.mapper.selectShipmentById(allocated.getId()).getSolutionItemId()).isEqualTo(first.parts().getFirst().id());
            assertThat(f.mapper.selectShipmentById(next.getId()).getSolutionItemId()).isEqualTo(second.parts().getFirst().id());
            assertThat(f.admin(()->f.service.solutionVersions(id))).extracting(AfterSalesPlanView::status).containsExactly("APPROVED","APPROVED");
        }
    }

    @Test void reducedApprovalCannotEraseAllocatedPartsAndStaleEditorsCannotReplaceCurrentVersion() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var initial=approve(f,id,plan(null,"Motor",2,false));f.admin(()->f.service.addShipment(id,shipment(initial,2)));
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REVISE","数量复核",initial.id())));
            var reduced=f.admin(()->f.service.saveSolution(id,plan(initial.id(),"Motor",1,false))).getSolutionVersion();f.admin(()->f.service.confirmSolution(id,reduced.id()));
            assertCode("AFTER_SALES_PART_ALLOCATION_CONFLICT",()->f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,reduced.id()))));
            assertCode("AFTER_SALES_SOLUTION_CONFLICT",()->f.admin(()->f.service.saveSolution(id,plan(initial.id(),"Motor",4,false))));
            var corrected=approve(f,id,plan(reduced.id(),"Motor",2,false));assertThat(corrected.revision()).isEqualTo(3);
            assertThat(f.admin(()->f.service.solutionVersions(id))).extracting(AfterSalesPlanView::status).containsExactly("APPROVED","SUPERSEDED","APPROVED");
        }
    }

    @Test void legacyShipmentNeedsExplicitReconciliationAndPreservesHistoricalFacts() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);
            f.sql("INSERT INTO "+f.prefix+"after_sales_shipment(case_id,part_name,quantity,approval_source,created_at) VALUES("+id+",'historic motor',1,'LEGACY_UNLINKED','2025-01-01 12:00:00')");
            var old=f.mapper.selectShipments(id).getFirst();var p=f.admin(()->f.service.saveSolution(id,plan(null,"Motor",2,false))).getSolutionVersion();
            assertCode("FORBIDDEN",()->f.agent(()->f.service.reconcileShipment(id,old.getId(),p.parts().getFirst().id(),"check")));
            f.admin(()->f.service.reconcileShipment(id,old.getId(),p.parts().getFirst().id(),"核对原始补发单，明确为同款电机"));
            f.admin(()->f.service.confirmSolution(id,p.id()));f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,p.id())));
            f.admin(()->f.service.dispatchShipment(id,old.getId(),new AfterSalesDispatchRequest("DHL","LEGACY",AfterSalesPlanMysqlTest.shipping(),1)));
            var reconciled=f.mapper.selectShipmentById(old.getId());assertThat(reconciled.getApprovalSource()).isEqualTo("LEGACY_REVIEWED");
            assertThat(reconciled.getCreatedAt()).isEqualTo(old.getCreatedAt());assertThat(reconciled.getPartName()).isEqualTo("historic motor");
            assertThat(f.admin(()->f.service.get(id)).getSolutionVersion().parts().getFirst().allocatedQty()).isEqualTo(1);
        }
    }

    @Test void chargedPartsRequireExactMoneyAndResponsibilityAndCompanyScopeProtectsHistory() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var base=plan(null,"Motor",1,false);
            for(String value:List.of("-1","0.001","99999999999999999")) {
                var invalid=new AfterSalesSolutionRequest("test","repair",null,null,null,null,null,"IN_WARRANTY","CNY","customer damage",List.of(new AfterSalesSolutionRequest.Part("MOTOR","Motor",1,new BigDecimal(value),false)));
                assertCode("INVALID_PARAMETER",()->f.admin(()->f.service.saveSolution(id,invalid)));
            }
            var charged=new AfterSalesSolutionRequest("test","repair","private",null,"customer damage","replace",null,"OUT_OF_WARRANTY","USD","Customer bears the part cost",List.of(new AfterSalesSolutionRequest.Part("MOTOR","Motor",1,new BigDecimal("19.99"),false)));
            var unexplained=new AfterSalesSolutionRequest("test","repair",null,null,null,null,null,"OUT_OF_WARRANTY","USD",null,charged.parts());
            assertCode("INVALID_PARAMETER",()->f.admin(()->f.service.saveSolution(id,unexplained)));
            var p=approve(f,id,charged);assertThat(p.parts().getFirst().unitCharge()).isEqualByComparingTo("19.99");
            assertCode("FORBIDDEN",()->f.as("AGENT",99L,()->f.service.solutionVersions(id),"ROLE_WEB_AGENT","PERM_AFTER_SALES_READ"));
            assertThat(f.agent(()->f.service.solutionVersions(id)).getFirst().document().chargeReason()).isEqualTo("Customer bears the part cost");
        }
    }

    @Test void failedAuditRollsBackVersionItemsAndCaseNarrative() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);
            var broken=new AfterSalesServiceImpl((AfterSalesMapper)Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertAudit"))return 0;try{return method.invoke(f.mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
            }));
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.saveSolution(id,plan(null,"Motor",2,false))));
            assertThat(f.count("after_sales_solution_version")).isZero();assertThat(f.count("after_sales_solution_item")).isZero();assertThat(f.admin(()->f.service.get(id)).getSolution()).isNull();
            var saved=f.admin(()->f.service.saveSolution(id,plan(null,"Motor",2,false))).getSolutionVersion();f.admin(()->f.service.confirmSolution(id,saved.id()));
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.review(id,new AfterSalesReviewRequest("APPROVE",null,saved.id()))));
            assertThat(f.admin(()->f.service.get(id)).getStatus()).isEqualTo("UNDER_REVIEW");
            assertThat(f.admin(()->f.service.get(id)).getSolutionVersion().status()).isEqualTo("CONFIRMED");
        }
    }
}
