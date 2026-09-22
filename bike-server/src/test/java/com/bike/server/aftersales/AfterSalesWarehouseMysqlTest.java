package com.bike.server.aftersales;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;
import static com.bike.server.aftersales.AfterSalesPlanMysqlTest.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class AfterSalesWarehouseMysqlTest {
    static <T> T warehouse(AfterSalesMysqlTest.Fixture f,Supplier<T> work) {
        return f.as("WEB_OPERATOR",null,work,"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_LOGISTICS","PERM_AFTER_SALES_SCOPE_ALL","PERM_AFTER_SALES_ASSIGN");
    }
    static final AfterSalesCaseFilter ALL=AfterSalesCaseFilter.basic(null,null);

    @Test void listCountDashboardAndDirectAccessUseCurrentApprovedPartsOrExistingExecution() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);
            assertCode("FORBIDDEN",()->warehouse(f,()->f.service.get(id)));
            assertThat(warehouse(f,()->f.service.list(ALL,1,20)).total()).isZero();
            assertThat(warehouse(f,()->f.service.dashboard(ALL)).total()).isZero();
            var approved=approve(f,id,plan(null,"Motor",1,false));
            assertThat(warehouse(f,()->f.service.list(ALL,1,20)).records()).extracting(AfterSalesCaseView::getId).containsExactly(id);
            assertThat(warehouse(f,()->f.service.dashboard(ALL)).total()).isEqualTo(1);
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REVISE","revise",approved.id())));
            var draft=f.admin(()->f.service.saveSolution(id,plan(approved.id(),"Motor",1,false))).getSolutionVersion();
            assertCode("FORBIDDEN",()->warehouse(f,()->f.service.get(id)));
            assertThat(warehouse(f,()->f.service.list(ALL,1,20)).total()).isZero();
            f.admin(()->f.service.confirmSolution(id,draft.id()));
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("APPROVE",null,draft.id())));
            var shipment=warehouse(f,()->f.service.addShipment(id,shipment(draft,1)));
            warehouse(f,()->f.service.dispatchShipment(id,shipment.getId(),new AfterSalesDispatchRequest("DHL","WAREHOUSE-1",AfterSalesPlanMysqlTest.shipping(),1)));
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REVISE","new diagnostic",draft.id())));
            f.admin(()->f.service.saveSolution(id,plan(draft.id(),"Motor",1,false)));
            assertThat(warehouse(f,()->f.service.get(id)).getShipments()).hasSize(1);
            assertThat(warehouse(f,()->f.service.get(id)).getSolutionVersion().parts()).isEmpty();
        }
    }

    @Test void costsDiagnosticsMessagesFilesAndSearchCannotBypassTheWarehouseProjection() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);
            var charged=new AfterSalesSolutionRequest("secret diagnosis","repair","internal",null,"secret cause","install","instructions","OUT_OF_WARRANTY","USD","secret liability",List.of(new AfterSalesSolutionRequest.Part("MOTOR","Motor",1,new BigDecimal("19.99"),false)));
            approve(f,id,charged);
            f.admin(()->f.service.addMessage(id,new AfterSalesMessageRequest("private customer details","CUSTOMER")));
            f.sql("UPDATE "+f.prefix+"after_sales_case SET description='secret-search-marker',frame_sn='secret-frame-marker' WHERE id="+id);
            var c=warehouse(f,()->f.service.get(id));
            assertThat(c.getFrameSn()).isNull();assertThat(c.getDescription()).isNull();assertThat(c.getContactEmail()).isNull();
            assertThat(c.getTechnicalJudgment()).isNull();assertThat(c.getSolution()).isNull();assertThat(c.getInternalNote()).isNull();
            assertThat(c.getSolutionVersion().parts().getFirst().unitCharge()).isNull();
            assertThat(c.getSolutionVersion().document().chargeReason()).isNull();assertThat(c.getSolutionVersion().document().parts()).isEmpty();
            assertThat(c.getMessages()).isEmpty();assertThat(c.getHistory()).isEmpty();
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->f.service.messages(id)));
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->f.service.solutionVersions(id)));
            var files=new AfterSalesAttachmentService(f.mapper,null,null);
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->files.list(id)));
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->files.download(id,1)));
            var companies=new AfterSalesCompanyService(f.mapper);
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->companies.get(8)));
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->companies.assign(id,7L)));
            for(String search:List.of("secret-search-marker","secret-frame-marker")) {
                var filter=AfterSalesCaseFilter.basic(search,null);
                assertThat(warehouse(f,()->f.service.list(filter,1,20)).total()).isZero();
                assertThat(warehouse(f,()->f.service.dashboard(filter)).total()).isZero();
            }
            var filter=new AfterSalesCaseFilter(null,null,null,null,"secret",null,null,null,null,null,null,null);
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->f.service.list(filter,1,20)));
            assertCode("PERMISSION_DENIED",()->warehouse(f,()->f.service.addMessage(id,new AfterSalesMessageRequest("bypass","CUSTOMER"))));
        }
    }

    @Test void logisticsPermissionDoesNotGrantCompanyScopeOrOverrideExternalIdentity() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);approve(f,id,plan(null,"Motor",1,false));
            String[] permissions={"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_LOGISTICS"};
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->f.service.get(id),permissions));
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.dashboard(ALL),permissions).total()).isZero();
            var companies=new AfterSalesCompanyService(f.mapper);f.admin(()->companies.setSalespeople(8,List.of(7L)));
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.get(id),permissions).getFrameSn()).isNull();
            f.admin(()->companies.setSalespeople(8,List.of()));
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.list(ALL,1,20),permissions).total()).isZero();
            assertCode("FORBIDDEN",()->f.as("AGENT",99L,()->f.service.get(id),"ROLE_WEB_AGENT","PERM_AFTER_SALES_READ","PERM_AFTER_SALES_LOGISTICS","PERM_AFTER_SALES_SCOPE_ALL"));
        }
    }
}
