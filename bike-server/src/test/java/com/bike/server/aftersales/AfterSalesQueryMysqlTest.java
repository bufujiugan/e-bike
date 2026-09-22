package com.bike.server.aftersales;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;

/** Production SQL against isolated MySQL tables: scope, aggregates and drill-down must agree. */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class AfterSalesQueryMysqlTest {
    private static AfterSalesCaseFilter queue(String queue) {
        return new AfterSalesCaseFilter(null, null, null, null, null, null, null, null, null, null, null, queue);
    }

    @Test void everyCardDrillsIntoMatchingCasesWithoutCountingShipmentRowsTwice() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            String[] statuses = { "DRAFT", "SUBMITTED", "UNDER_REVIEW", "NEEDS_INFO", "APPROVED", "IN_PROGRESS", "WAITING_PARTS", "SHIPPED", "RESOLVED", "CLOSED", "REJECTED" };
            for (String status : statuses) {
                long id = f.submittedCase();
                f.sql("UPDATE " + f.prefix + "after_sales_case SET status='" + status + "',closed_at="
                    + (status.equals("CLOSED") ? "CURRENT_TIMESTAMP(3)" : "NULL") + " WHERE id=" + id);
                if (status.equals("WAITING_PARTS")) f.sql("INSERT INTO " + f.prefix + "after_sales_shipment(case_id,part_name,quantity) VALUES(" + id + ",'motor',1),(" + id + ",'battery',2)");
            }
            var counts = f.agent(() -> f.service.dashboard(queue(null)));
            assertThat(counts).isEqualTo(new AfterSalesDashboardView(11, 1, 1, 1, 3, 1, 1, 1, 1));
            for (var entry : Map.of("PENDING",1L,"REVIEW",1L,"WAITING_CUSTOMER",1L,"PROCESSING",3L,
                "WAITING_SHIPMENT",1L,"SHIPPED",1L,"COMPLETED",1L,"COMPLETED_MONTH",1L).entrySet()) {
                var page = f.agent(() -> f.service.list(queue(entry.getKey()),1,100));
                assertThat(page.total()).as(entry.getKey()).isEqualTo(entry.getValue());
                assertThat(page.records()).hasSize(entry.getValue().intValue());
            }
            // Cards remain available when a particular queue/status is selected.
            assertThat(f.agent(() -> f.service.dashboard(queue("WAITING_SHIPMENT")))).isEqualTo(counts);
            assertThat(f.agent(() -> f.service.dashboard(AfterSalesCaseFilter.basic(null,"CLOSED")))).isEqualTo(counts);
            var first = f.agent(() -> f.service.list(queue(null),1,4));
            var second = f.agent(() -> f.service.list(queue(null),2,4));
            assertThat(first.total()).isEqualTo(11); assertThat(second.total()).isEqualTo(11);
            assertThat(first.records()).extracting(AfterSalesCaseView::getId).doesNotContainAnyElementsOf(second.records().stream().map(AfterSalesCaseView::getId).toList());
        }
    }

    @Test void companyEmployeesAndSalesScopeProtectCountsAsWellAsRowsAndRevocationIsImmediate() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            long own = f.submittedCase();
            long foreign = f.admin(() -> f.service.create(request(null,99L,null))).getId();
            f.admin(() -> f.service.submit(foreign));
            var filter = AfterSalesCaseFilter.basic("Agent",null);
            assertThat(f.agent(() -> f.service.dashboard(filter)).total()).isEqualTo(1);
            assertThat(f.asUser(75,"AGENT",8L,() -> f.service.dashboard(filter),"ROLE_WEB_AGENT","ROLE_SUPER_ADMIN").total()).isEqualTo(1);
            assertThat(f.as("AGENT",99L,() -> f.service.dashboard(filter),"PERM_AFTER_SALES_READ").pending()).isEqualTo(1);
            assertThat(f.as("WEB_OPERATOR",null,() -> f.service.dashboard(filter),"PERM_AFTER_SALES_READ").total()).isZero();
            var company = new AfterSalesCompanyService(f.mapper);
            f.admin(() -> company.setSalespeople(8,java.util.List.of(7L)));
            assertThat(f.as("WEB_OPERATOR",null,() -> f.service.dashboard(filter),"PERM_AFTER_SALES_READ").pending()).isEqualTo(1);
            assertThat(f.as("WEB_OPERATOR",null,() -> f.service.list(filter,1,20),"PERM_AFTER_SALES_READ").records()).extracting(AfterSalesCaseView::getId).containsExactly(own);
            var foreignSearch = AfterSalesCaseFilter.basic("Agent B",null);
            assertThat(f.agent(() -> f.service.dashboard(foreignSearch)).total()).isZero();
            assertThat(f.agent(() -> f.service.list(foreignSearch,1,20)).total()).isZero();
            f.admin(() -> company.setSalespeople(8,java.util.List.of()));
            assertThat(f.as("WEB_OPERATOR",null,() -> f.service.dashboard(filter),"PERM_AFTER_SALES_READ").total()).isZero();
            assertCode("PERMISSION_DENIED", () -> f.as("PDA_OPERATOR",null,() -> f.service.dashboard(filter),"ROLE_SUPER_ADMIN"));
        }
    }

    @Test void combinedFiltersUseContactSnapshotsLiteralSearchAndInclusiveEndDate() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            long match = f.submittedCase(), wrongDay = f.submittedCase(), wrongCountry = f.submittedCase();
            f.sql("UPDATE " + f.prefix + "after_sales_case SET product_model_id=4,frame_sn='FRAME_%!',fault_type='MOTOR',created_at='2026-08-31 23:59:59.999'");
            f.sql("UPDATE " + f.prefix + "after_sales_case_contact SET company_name='Snapshot Bikes',contact_name='Ada_100%',country_code='GB',assignee_name='Technician'");
            f.sql("UPDATE " + f.prefix + "after_sales_case SET created_at='2026-09-01 00:00:00' WHERE id=" + wrongDay);
            f.sql("UPDATE " + f.prefix + "after_sales_case_contact SET country_code='FR' WHERE case_id=" + wrongCountry);
            var filter = new AfterSalesCaseFilter("100%", "SUBMITTED", "Snapshot", "Ada_", "_%!", "Model", "MOTOR", "gb", "Technician", LocalDate.of(2026,8,31),LocalDate.of(2026,8,31),null);
            assertThat(f.agent(() -> f.service.list(filter,1,20)).records()).extracting(AfterSalesCaseView::getId).containsExactly(match);
            assertThat(f.agent(() -> f.service.dashboard(filter)).pending()).isEqualTo(1);
            assertThat(f.agent(() -> f.service.list(AfterSalesCaseFilter.basic("AdaX100Y",null),1,20)).total()).isZero();
            assertThat(f.agent(() -> f.service.list(AfterSalesCaseFilter.basic("%",null),1,20)).total()).isEqualTo(3);
            assertThat(f.agent(() -> f.service.list(AfterSalesCaseFilter.basic("' OR 1=1 --",null),1,20)).total()).isZero();
            // Later company-profile edits do not change the case contact/name being searched.
            f.sql("UPDATE " + f.prefix + "agent SET contact_name='New contact' WHERE id=8");
            assertThat(f.agent(() -> f.service.dashboard(filter)).total()).isEqualTo(1);
        }
    }

    @Test void completedMonthUsesClosedAtWithHalfOpenMonthBoundariesAndEmptyCountsAreZero() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            assertThat(f.agent(() -> f.service.dashboard(queue(null)))).isEqualTo(new AfterSalesDashboardView(0,0,0,0,0,0,0,0,0));
            LocalDate start = LocalDate.now().withDayOfMonth(1), end = start.plusMonths(1);
            long first = f.submittedCase(), previous = f.submittedCase(), next = f.submittedCase();
            f.sql("UPDATE " + f.prefix + "after_sales_case SET status='CLOSED',created_at='2025-01-01',closed_at='" + start + " 00:00:00'");
            f.sql("UPDATE " + f.prefix + "after_sales_case SET closed_at='" + start.minusDays(1) + " 23:59:59.999' WHERE id=" + previous);
            f.sql("UPDATE " + f.prefix + "after_sales_case SET closed_at='" + end + " 00:00:00' WHERE id=" + next);
            var dashboard = f.agent(() -> f.service.dashboard(queue(null)));
            assertThat(dashboard.completed()).isEqualTo(3); assertThat(dashboard.completedThisMonth()).isEqualTo(1);
            assertThat(f.agent(() -> f.service.list(queue("COMPLETED_MONTH"),1,20)).records()).extracting(AfterSalesCaseView::getId).containsExactly(first);
        }
    }

    @Test void invalidFilterNeverSilentlyBecomesAnUnfilteredQuery() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            assertCode("INVALID_PARAMETER", () -> f.agent(() -> f.service.list(queue("UNKNOWN"),1,20)));
            assertCode("INVALID_PARAMETER", () -> f.agent(() -> f.service.dashboard(AfterSalesCaseFilter.basic(null,"INVALID"))));
            var reversed = new AfterSalesCaseFilter(null,null,null,null,null,null,null,null,null,LocalDate.of(2026,9,2),LocalDate.of(2026,9,1),null);
            assertCode("INVALID_PARAMETER", () -> f.agent(() -> f.service.dashboard(reversed)));
        }
    }
}
