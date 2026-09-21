package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import com.bike.server.common.BusinessException;
import com.bike.server.pi.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiLogisticsMysqlTest {
    @Test void arbitraryNumberCannotClaimShipmentAndPlanCanBecomeRealDispatch() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=PiDispatchMysqlTest.ready(f);
            code(()->f.tx(()->f.logistics.save(pi.getId(),null,request("fake",null,"SHIPPED",null))),"LOGISTICS_ACTUAL_BATCH_REQUIRED");
            long plan=f.tx(()->f.logistics.save(pi.getId(),null,request("plan",null,"PLANNED",null)));
            var batch=f.tx(()->f.dispatch.dispatch(pi.getId(),PiDispatchMysqlTest.request(pi,"QR120")));
            f.tx(()->f.logistics.save(pi.getId(),plan,request("confirm",batch.id(),"SHIPPED",1)));
            var shipped=f.logisticsMapper.get(plan);
            assertThat(shipped.getRevision()).isEqualTo(2);assertThat(shipped.getBatchNo()).isEqualTo(batch.batchNo());
            assertThat(shipped.getShippedQtySnapshot()).isEqualTo(2);assertThat(shipped.getLinkType()).isEqualTo("ACTUAL_BATCH");
            assertThat(f.tx(()->f.logistics.history(pi.getId(),plan))).hasSize(2);
            long stock=((Number)f.dispatchMapper.vehicles(batch.id()).getFirst().get("stockOutId")).longValue();
            code(()->f.tx(()->f.stock.cancelStockOut(stock,"在途禁止撤销")),"LOGISTICS_SHIPPED");
            code(()->f.tx(()->f.logistics.save(pi.getId(),plan,request("edit-shipped",batch.id(),"PLANNED",2))),"LOGISTICS_REVISION_CONFLICT");
            var withdraw=new PiLogisticsWithdraw("withdraw",2,"承运商已取消，确认货物仍在仓库");
            f.tx(()->f.logistics.withdraw(pi.getId(),plan,withdraw));
            f.tx(()->f.logistics.withdraw(pi.getId(),plan,withdraw));
            assertThat(f.logisticsMapper.get(plan).getRevision()).isEqualTo(3);
            assertThat(f.tx(()->f.logistics.history(pi.getId(),plan))).hasSize(3);
            f.tx(()->f.stock.cancelStockOut(stock,"授权物流撤回后撤销一台"));
            f.tx(()->f.logistics.save(pi.getId(),plan,request("reship",batch.id(),"SHIPPED",3)));
            assertThat(f.logisticsMapper.get(plan).getShippedQtySnapshot()).isEqualTo(1);
            assertThat(f.tx(()->f.logistics.history(pi.getId(),plan))).hasSize(4);
        }
    }
    @Test void sameCommandRetriesOnceAndChangedOrStaleCommandsCannotOverwrite() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=PiDispatchMysqlTest.ready(f);var batch=f.tx(()->f.dispatch.dispatch(pi.getId(),PiDispatchMysqlTest.request(pi,"QR120")));
            var original=request("create",batch.id(),"PLANNED",null);
            long id=f.tx(()->f.logistics.save(pi.getId(),null,original));
            assertThat(f.tx(()->f.logistics.save(pi.getId(),null,original))).isEqualTo(id);
            code(()->f.tx(()->f.logistics.save(pi.getId(),null,request("create",batch.id(),"SHIPPED",null))),"LOGISTICS_REQUEST_CONFLICT");
            code(()->f.tx(()->f.logistics.save(pi.getId(),null,request("another",batch.id(),"SHIPPED",null))),"LOGISTICS_BATCH_EXISTS");
            f.tx(()->f.logistics.save(pi.getId(),id,request("publish",batch.id(),"SHIPPED",1)));
            code(()->f.tx(()->f.logistics.withdraw(pi.getId(),id,new PiLogisticsWithdraw("stale",1,"过期视图"))),"LOGISTICS_REVISION_CONFLICT");
            assertThat(f.tx(()->f.logistics.history(pi.getId(),id))).hasSize(2);
        }
    }
    @Test void cancelledBatchOrDifferentPiCannotBeClaimedAndLegacyShipmentBlocksUntilWithdrawn() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=PiDispatchMysqlTest.ready(f);var batch=f.tx(()->f.dispatch.dispatch(pi.getId(),PiDispatchMysqlTest.request(pi,"QR120")));
            var vehicles=f.dispatchMapper.vehicles(batch.id());
            long stock=((Number)vehicles.getFirst().get("stockOutId")).longValue();
            f.exec("INSERT INTO @pi_logistics(pi_id,batch_no,carrier,tracking_no,status) VALUES("+pi.getId()+",'old-manual','carrier','old-track','SHIPPED')");
            code(()->f.tx(()->f.stock.cancelStockOut(stock,"旧物流未关联不能静默撤销")),"LOGISTICS_SHIPPED");
            long legacy=f.scalar("SELECT id FROM @pi_logistics WHERE batch_no='old-manual'");
            f.tx(()->f.logistics.withdraw(pi.getId(),legacy,new PiLogisticsWithdraw("legacy-withdraw",1,"人工核实历史物流已取消")));
            for(var vehicle:vehicles)f.tx(()->f.stock.cancelStockOut(((Number)vehicle.get("stockOutId")).longValue(),"批次全部撤销"));
            code(()->f.tx(()->f.logistics.save(pi.getId(),null,request("empty",batch.id(),"SHIPPED",null))),"LOGISTICS_BATCH_EMPTY");
            code(()->f.tx(()->f.logistics.save(pi.getId(),null,request("wrong",9999L,"SHIPPED",null))),"PI_BATCH_MISMATCH");
        }
    }
    private static PiLogisticsRequest request(String key,Long batch,String state,Integer version) {
        return new PiLogisticsRequest("PLAN-"+key,"carrier","TRACK-"+key,state,"验收",batch,key,version);
    }
    private static void code(Supplier<?> action,String expected){assertThatThrownBy(action::get).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(expected));}
}
