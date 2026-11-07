package com.bike.server.stock;

import com.bike.server.pi.*;
import com.bike.server.common.*;
import com.bike.server.order.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiFulfillmentConfigMysqlTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private PiFulfillmentProfileMapper install(SkuCommitmentMysqlTest.Fixture f) throws Exception {
        for(String name:List.of("PiFulfillmentProfileMapper","PiFulfillmentSchemaMapper")) {
            try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
                var xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",f.prefix);
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),f.session.getConfiguration(),name,f.session.getConfiguration().getSqlFragments()).parse();
            }
        }
        f.session.update("com.bike.server.pi.PiFulfillmentSchemaMapper.createProfiles");f.tables.add("pi_fulfillment_profile");
        return f.session.getMapper(PiFulfillmentProfileMapper.class);
    }
    private PiFulfillmentProfileRequest request() {
        return new PiFulfillmentProfileRequest("UNIFIED","1","后付款提货","DOMESTIC","Company","TPL","1",
            new PiFulfillmentRules("CREDIT","CUSTOMER_PICKUP",false,false,false,false,false,true,List.of("CI","RD","CMR"),List.of(1L)),"测试");
    }
    @Test void publishedVersionsRoundTripWithoutOverwritingHistory() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var mapper=install(f);var request=request();
            assertThat(f.tx(()->mapper.insertProfile(request,serialize(request),1L))).isEqualTo(1);
            var stored=mapper.selectVersion("UNIFIED","1");
            assertThat(json.readValue(stored.profileJson(),PiFulfillmentProfileRequest.class).rules().credit()).isTrue();
            assertThatThrownBy(()->f.tx(()->mapper.insertProfile(request,serialize(request),1L))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            f.tx(()->mapper.updateEnabled(stored.id(),false));
            assertThat(mapper.selectProfile(stored.id()).enabled()).isFalse();
            assertThat(mapper.selectProfile(stored.id()).profileJson()).isEqualTo(stored.profileJson());
        }
    }
    @Test void creditReservationIsIdempotentAndProtectsWarehouseAgainstSecondOrder() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var profileMapper=install(f);var request=request();f.tx(()->profileMapper.insertProfile(request,serialize(request),1L));
            long profileId=profileMapper.selectVersion("UNIFIED","1").id();
            var first=f.tx(()->f.pis.create(piRequest(profileId)));var second=f.tx(()->f.pis.create(piRequest(profileId)));
            var businessMapper=f.session.getMapper(PiBusinessMapper.class);
            var profiles=new PiFulfillmentProfileServiceImpl(profileMapper,mock(PiTemplateMapper.class),businessMapper,f.session.getMapper(OperationLogMapper.class),json,f.validation.getValidator());
            var workflow=mock(PiWorkflowMapper.class);
            when(workflow.selectSteps(anyLong())).thenReturn(List.of(new PiWorkflowStepRecord(13,"CREDIT_APPROVED","后付款授权","COMPLETED",1L,LocalDateTime.now(),"已批准","{}")));
            var business=new PiBusinessServiceImpl(businessMapper,f.session.getMapper(PiMapper.class),f.piSeries,workflow,f.orders,f.reservations,f.inventory,f.session.getMapper(OperationLogMapper.class),json);
            ReflectionTestUtils.setField(business,"profiles",profiles);ReflectionTestUtils.setField(business,"profileMapper",profileMapper);
            assertThat(f.tx(()->business.reserveAfterPayment(first,BigDecimal.ZERO))).isTrue();
            assertThat(f.tx(()->business.reserveAfterPayment(first,BigDecimal.ZERO))).isTrue();
            assertThat(f.piSeries.fulfillment(first.getId())).hasSize(1).allSatisfy(line->assertThat(line.reservationStatus()).isEqualTo("CREDIT_HELD"));
            assertThat(businessMapper.countWarehouseReserved(1,10,0)).isEqualTo(1);
            assertThat(f.inventory.selectFulfillmentSnapshot(10,0).availableForOrder()).isZero();
            assertThat(f.tx(()->business.reserveAfterPayment(second,BigDecimal.ZERO))).isFalse();
            assertThat(f.piSeries.fulfillment(second.getId())).isEmpty();
            assertThat(f.session.getMapper(PiMapper.class).paymentTotal(first.getId())).isZero();
            long orderId=f.piSeries.fulfillment(first.getId()).getFirst().orderId();
            f.tx(()->f.renew.renew(orderId,24,"不能把授信保留变回限时"));
            assertThat(f.reservations.selectForUpdate(orderId).holdMode()).isEqualTo("CREDIT");
            assertThat(f.tx(()->f.reservations.release(orderId))).isEqualTo(1);
            assertThat(businessMapper.countWarehouseReserved(1,10,0)).isZero();
        }
    }
    private PiCreateRequest piRequest(long profileId) {
        var document=new PiDocumentSnapshot("Seller",null,null,null,null,"Bank","Account","Company","后付款","客户提货","TPL","1");
        var node=json.valueToTree(document);
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("business",json.valueToTree(new PiBusinessTerms(1L,false,"Company","NEW_BUSINESS",profileId,LocalDate.of(2099,1,1))));
        try {document=json.treeToValue(node,PiDocumentSnapshot.class);}catch(Exception error){throw new IllegalStateException(error);}
        return new PiCreateRequest(1L,null,null,"DOMESTIC","CNY",null,null,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,document,List.of(new PiItemRequest(10L,1,new BigDecimal("100"),null,null)));
    }
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
}
