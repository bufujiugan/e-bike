package com.bike.server.aftersales;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;
import static org.assertj.core.api.Assertions.*;

/** Production mapper and services against isolated, foreign-key constrained MySQL tables. */
@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class AfterSalesCompanyMysqlTest {
    private static AfterSalesContact contact(String name) {
        return new AfterSalesContact(name,"+49 123 456","service@example.com","de","Bavaria","Munich","Main Street 10","80000");
    }
    private static AfterSalesCaseCreateRequest draft(AfterSalesContact contact, LocalDate date) {
        return new AfterSalesCaseCreateRequest(null,null,null,"FRAME-NO-MODEL",null,null,null,null,null,null,"MOTOR",null,null,"Motor failure",null,"PENDING",contact,date);
    }

    @Test void companyDefaultsAndCaseSnapshotsStayIndependentAcrossUpdates() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            assertThat(f.agent(()->companies.get(8)).contactName()).isEqualTo("Agent contact");
            assertThat(f.agent(()->companies.get(8)).source()).isEqualTo("MASTER");
            var original=f.agent(()->f.service.create(draft(null,null)));
            assertThat(original.getSnapshotSource()).isEqualTo("MASTER");
            assertThat(original.getCompanyName()).isEqualTo("Agent A");
            f.admin(()->companies.save(8,contact("New contact")));
            assertThat(f.agent(()->f.service.get(original.getId())).getContactName()).isEqualTo("Agent contact");
            var newer=f.agent(()->f.service.create(draft(null,LocalDate.now().minusDays(2))));
            assertThat(newer.getContactName()).isEqualTo("New contact");
            assertThat(newer.getCountryCode()).isEqualTo("DE");
            assertThat(newer.getSnapshotSource()).isEqualTo("PROFILE");
            assertThat(newer.getEndCustomerPurchaseDate()).isEqualTo(LocalDate.now().minusDays(2));
            f.admin(()->companies.save(8,contact("Third contact")));
            assertThat(f.agent(()->f.service.get(newer.getId())).getContactName()).isEqualTo("New contact");
            var manual=f.agent(()->f.service.update(newer.getId(),draft(contact("Case only"),null)));
            assertThat(manual.getContactName()).isEqualTo("Case only");
            assertThat(manual.getSnapshotSource()).isEqualTo("MANUAL");
            assertThat(manual.getEndCustomerPurchaseDate()).isNull();
            assertThat(f.agent(()->companies.get(8)).contactName()).isEqualTo("Third contact");
            assertThat(f.admin(()->companies.history(8))).extracting(AfterSalesHistoryView::action).containsExactly("COMPANY_CONTACT_UPDATED","COMPANY_CONTACT_UPDATED");
        }
    }

    @Test void salesOnlyReadsAssignedCompaniesAndRevocationTakesEffectOnNextRequest() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            long own=f.agent(()->f.service.create(draft(null,null))).getId();
            long other=f.as("AGENT",99L,()->f.service.create(draft(null,null)),"PERM_AFTER_SALES_WRITE").getId();
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.list(null,null,1,20),"PERM_AFTER_SALES_READ").total()).isZero();
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->f.service.get(own),"PERM_AFTER_SALES_READ"));
            assertThat(f.as("WEB_OPERATOR",null,companies::list,"PERM_AFTER_SALES_READ")).isEmpty();
            f.admin(()->companies.setSalespeople(8,List.of(7L)));
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.list(null,null,1,20),"PERM_AFTER_SALES_READ").records()).extracting(AfterSalesCaseView::getId).containsExactly(own);
            assertThat(f.as("WEB_OPERATOR",null,companies::list,"PERM_AFTER_SALES_READ")).extracting(AfterSalesCompanyView::agentId).containsExactly(8L);
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.get(own),"PERM_AFTER_SALES_READ").getId()).isEqualTo(own);
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->f.service.get(other),"PERM_AFTER_SALES_READ"));
            assertCode("PERMISSION_DENIED",()->f.as("WEB_OPERATOR",null,()->companies.save(8,contact("Blocked")),"PERM_AFTER_SALES_READ"));
            assertCode("PERMISSION_DENIED",()->f.as("WEB_OPERATOR",null,()->companies.history(8),"PERM_AFTER_SALES_READ"));
            assertCode("PERMISSION_DENIED",()->f.as("WEB_OPERATOR",null,()->f.service.addMessage(own,new AfterSalesMessageRequest("blocked","CUSTOMER")),"PERM_AFTER_SALES_READ"));
            f.admin(()->companies.setSalespeople(8,List.of()));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->f.service.get(own),"PERM_AFTER_SALES_READ"));
            assertThat(f.as("WEB_OPERATOR",null,()->f.service.list(null,null,1,20),"PERM_AFTER_SALES_READ").total()).isZero();
        }
    }

    @Test void companyMembersShareCasesButAccidentalInternalPermissionsNeverCrossCompanyBoundary() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            long id=f.agent(()->f.service.create(draft(null,null))).getId();
            assertThat(f.asUser(75,"AGENT",8L,()->f.service.get(id),"PERM_AFTER_SALES_READ").getId()).isEqualTo(id);
            assertThat(f.admin(()->companies.members(8))).extracting(AfterSalesMemberView::id).containsExactly(73L,75L);
            assertThat(f.as("AGENT",8L,companies::list,"ROLE_SUPER_ADMIN")).extracting(AfterSalesCompanyView::agentId).containsExactly(8L);
            assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->companies.get(99),"ROLE_SUPER_ADMIN"));
            assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->companies.save(8,contact("blocked")),"ROLE_SUPER_ADMIN"));
            assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->companies.setSalespeople(8,List.of(7L)),"ROLE_SUPER_ADMIN"));
            assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->companies.assign(id,70L),"ROLE_SUPER_ADMIN"));
            assertCode("FORBIDDEN",()->f.as("AGENT",8L,()->companies.history(8),"ROLE_SUPER_ADMIN"));
            assertCode("PERMISSION_DENIED",()->f.as("PDA_OPERATOR",null,companies::list,"ROLE_SUPER_ADMIN"));
        }
    }

    @Test void assigningRequiresAnEnabledInternalProcessorWithCompanyScopeAndKeepsNameSnapshot() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper); long id=f.submittedCase();
            assertThat(f.admin(companies::staffOptions).get("assignees")).extracting(AfterSalesReferenceOption::id).containsExactlyInAnyOrder(70L,71L);
            for(long invalid:List.of(7L,72L,73L,74L,75L,999L,71L)) assertCode("INVALID_PARAMETER",()->f.admin(()->companies.assign(id,invalid)));
            f.admin(()->companies.setSalespeople(8,List.of(71L)));
            assertThat(f.admin(()->companies.assign(id,71L)).getAssigneeId()).isEqualTo(71L);
            f.sql("UPDATE "+f.prefix+"sys_user SET display_name=REPEAT('X',100),username=REPEAT('Y',64) WHERE id=70");
            var assigned=f.admin(()->companies.assign(id,70L));
            assertThat(assigned.getAssigneeName()).hasSize(167);
            f.sql("UPDATE "+f.prefix+"sys_user SET display_name='Renamed' WHERE id=70");
            assertThat(f.agent(()->f.service.get(id)).getAssigneeName()).hasSize(167);
            f.sql("UPDATE "+f.prefix+"after_sales_case SET internal_note='private' WHERE id="+id);
            // Same-assignee early return must be redacted too.
            assertThat(f.as("WEB_OPERATOR",null,()->companies.assign(id,70L),"PERM_AFTER_SALES_ASSIGN","PERM_AFTER_SALES_SCOPE_ALL").getInternalNote()).isNull();
            assertThat(f.admin(()->companies.assign(id,null)).getAssigneeName()).isNull();
            assertThat(f.admin(()->f.service.history(id))).extracting(AfterSalesHistoryView::action).contains("CASE_ASSIGNED");
            f.sql("UPDATE "+f.prefix+"after_sales_case SET status='CLOSED' WHERE id="+id);
            assertCode("INVALID_STATUS_TRANSITION",()->f.admin(()->companies.assign(id,70L)));
        }
    }

    @Test void salesConfigurationRejectsDuplicatesDisabledExternalAndRemovedPermissions() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            for(var invalid:List.of(List.of(7L,7L),List.of(72L),List.of(73L),List.of(74L),List.of(999L))) assertCode("INVALID_PARAMETER",()->f.admin(()->companies.setSalespeople(8,invalid)));
            f.sql("UPDATE "+f.prefix+"sys_role SET enabled=0 WHERE id=1");
            assertCode("INVALID_PARAMETER",()->f.admin(()->companies.setSalespeople(8,List.of(7L))));
            assertThat(f.count("after_sales_sales_agent")).isZero();
        }
    }

    @Test void companyAndAssignmentAuditFailureRollsBackDataChanges() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            f.admin(()->companies.save(8,contact("Original"))); f.admin(()->companies.setSalespeople(8,List.of(7L)));
            long id=f.submittedCase();
            var broken=new AfterSalesCompanyService((AfterSalesMapper)Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertCompanyAudit")||method.getName().equals("insertAudit")) return 0;
                try{return method.invoke(f.mapper,args);}catch(InvocationTargetException error){throw error.getCause();}
            }));
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.save(8,contact("Lost"))));
            assertThat(f.agent(()->companies.get(8)).contactName()).isEqualTo("Original");
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.setSalespeople(8,List.of(71L))));
            assertThat(f.admin(()->companies.salespeople(8))).extracting(AfterSalesReferenceOption::id).containsExactly(7L);
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.assign(id,70L)));
            assertThat(f.agent(()->f.service.get(id)).getAssigneeId()).isNull();
            assertThat(f.admin(()->companies.history(8))).hasSize(2);
        }
    }

    @Test void invalidContactAndFuturePurchaseDateLeaveNoPartialRecords() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            var companies=new AfterSalesCompanyService(f.mapper);
            for(var invalid:List.of(new AfterSalesContact("A",null,"invalid",null,null,null,null,null),new AfterSalesContact("A",null,null,"ZZ",null,null,null,null),new AfterSalesContact("A\nB",null,null,null,null,null,null,null))) {
                assertCode("INVALID_PARAMETER",()->f.admin(()->companies.save(8,invalid)));
                assertCode("INVALID_PARAMETER",()->f.agent(()->f.service.create(draft(invalid,null))));
            }
            assertCode("INVALID_PARAMETER",()->f.agent(()->f.service.create(draft(null,LocalDate.now().plusDays(1)))));
            assertThat(f.count("after_sales_company_profile")).isZero(); assertThat(f.count("after_sales_case")).isZero(); assertThat(f.count("operation_log")).isZero();
        }
    }
}
