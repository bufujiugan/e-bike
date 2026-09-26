package com.bike.server.aftersales;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.assertCode;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class AfterSalesAggregateCodeMysqlTest {
    private static AfterSalesCaseCreateRequest request(String qr, Long vehicleId, String frame) {
        return new AfterSalesCaseCreateRequest(null, null, vehicleId, frame, null, null, null, null,
            null, null, "MOTOR", null, null, "Aggregate code test", null, "PENDING", null, qr, null);
    }
    private static AfterSalesCaseFilter filter(String qr) {
        return new AfterSalesCaseFilter(null,null,null,null,null,null,null,null,null,null,null,null,qr);
    }

    @Test void ownCodeAndTokenResolveRealVehicleAndSearchCountsAgree() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            var created = f.agent(() -> f.service.create(request("BIKE:55:test", null, null)));
            assertThat(created.getVehicleId()).isEqualTo(55);
            assertThat(created.getVehicleQrCode()).isEqualTo("BIKE:55:test");
            assertThat(created.getFrameSn()).isEqualTo("F55");
            assertThat(created.getMotorSn()).isEqualTo("M55");
            assertThat(f.agent(() -> f.service.submit(created.getId())).getStatus()).isEqualTo("SUBMITTED");
            assertThat(f.agent(() -> f.service.create(request("test55", null, null))).getVehicleId()).isEqualTo(55);
            assertThat(f.agent(() -> f.service.list(filter("BIKE:55:test"),1,20)).total()).isEqualTo(2);
            assertThat(f.agent(() -> f.service.dashboard(filter("BIKE:55:test"))).total()).isEqualTo(2);
            assertThat(f.agent(() -> f.service.list(AfterSalesCaseFilter.basic("BIKE:55:test",null),1,20)).total()).isEqualTo(2);
            assertThat(f.agent(() -> f.service.list(filter("%"),1,20)).total()).isZero();
        }
    }

    @Test void invalidAndForeignCodesNeverFallBackToPostedFrameOrId() throws Exception {
        try (var f = new AfterSalesMysqlTest.Fixture()) {
            assertCode("FORBIDDEN", () -> f.agent(() -> f.service.create(request("missing",55L,"F55"))));
            assertCode("FORBIDDEN", () -> f.agent(() -> f.service.create(request("BIKE:56:test",null,null))));
            assertCode("AFTER_SALES_VEHICLE_MISMATCH", () -> f.agent(() -> f.service.create(request("BIKE:55:test",56L,null))));
            assertCode("AFTER_SALES_VEHICLE_MISMATCH", () -> f.agent(() -> f.service.create(request("BIKE:55:test",null,"F56"))));
            assertThat(f.count("after_sales_case")).isZero();
            assertThat(f.as("AGENT",99L,() -> f.service.list(filter("BIKE:55:test"),1,20),"PERM_AFTER_SALES_READ").total()).isZero();
            assertCode("PERMISSION_DENIED", () -> f.as("WEB_OPERATOR",null,
                () -> f.service.list(filter("BIKE:55:test"),1,20),"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_LOGISTICS"));
            assertCode("PERMISSION_DENIED", () -> f.as("PDA_OPERATOR",null,
                () -> f.service.create(request("BIKE:55:test",null,null)),"ROLE_SUPER_ADMIN"));
        }
    }
}
