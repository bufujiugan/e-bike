package com.bike.server.production;

import com.bike.server.assembly.AssemblyService;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.config.*;
import com.bike.server.master.*;
import com.bike.server.system.UserMapper;
import com.bike.server.validation.SnRuleService;
import com.bike.server.vehicle.VehicleMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP入口使用真实Service，证明可配置岗位有权管理而工位和代理边界仍有效。 */
@WebMvcTest({ProductionController.class, MasterController.class})
@Import({SecurityConfig.class, ProductionServiceImpl.class, MasterServiceImpl.class, ProductionExcelService.class})
class ProductionManagementAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProductionMapper mapper;
    @MockitoBean ProductionGuard guard;
    @MockitoBean AssemblyService assembly;
    @MockitoBean VehicleMapper vehicles;
    @MockitoBean SnRuleService rules;
    @MockitoBean ScanFailureService failures;
    @MockitoBean EanBarcodeService eanBarcodes;
    @MockitoBean ProductionLabelService labels;
    @MockitoBean ProductionAttachmentService attachments;
    @MockitoBean MasterMapper masters;
    @MockitoBean OperationLogMapper logs;
    @MockitoBean UserMapper users;
    @MockitoBean JwtTokenService tokens;
    @MockitoBean com.bike.server.auth.TokenRevocationMapper revocations;

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor manager() {
        return user("supervisor").authorities(new SimpleGrantedAuthority("ROLE_FACTORY_SUPERVISOR"),
            new SimpleGrantedAuthority("PERM_PRODUCTION_MANAGE"));
    }

    @Test void supervisorCanReadProductionAndOnlyRequiredMasterData() throws Exception {
        mvc.perform(get("/api/production/skus").with(manager())).andExpect(status().isOk());
        mvc.perform(get("/api/production/templates").with(manager())).andExpect(status().isOk());
        mvc.perform(get("/api/master/models").with(manager())).andExpect(status().isOk());
        mvc.perform(get("/api/master/warehouses").with(manager())).andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(manager())).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/agents").with(manager())).andExpect(status().isForbidden());
        verify(masters, never()).select(eq("customers"), any());
        verify(masters, never()).select(eq("agents"), any());
    }

    @Test void supervisorCanSaveSkuAndDownloadImportTemplate() throws Exception {
        when(vehicles.countEnabledModel(27L)).thenReturn(1);
        when(mapper.selectTemplate(1)).thenReturn(Map.of("id",1));
        when(mapper.insertAudit(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        mvc.perform(post("/api/production/skus").with(manager()).contentType("application/json")
            .content("{\"modelId\":27,\"skuCode\":\"SUP-001\",\"productName\":\"管理测试\",\"templateId\":1,\"enabled\":true}"))
            .andExpect(status().isOk());
        verify(mapper).insertSku(argThat(sku -> sku.skuCode().equals("SUP-001")));
        mvc.perform(get("/api/production/skus/import-template").with(manager()))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test void supervisorCanCreateProductionWorkOrderButCannotCreateVehicleTask() throws Exception {
        var workOrder = new HashMap<String,Object>(Map.of(
            "id",7L,"skuId",1L,"batchNo","BATCH-20261015","plannedQuantity",10000,
            "workOrderNo","WO-20261015-001","status","RELEASED"));
        when(mapper.selectSku(1L)).thenReturn(Map.of("id",1L,"modelId",27L,"enabled",1,"skuCode","SKU-1"));
        when(vehicles.countEnabledModel(27L)).thenReturn(1);
        when(mapper.selectWorkOrderByRequestId("work-order-request-0001")).thenReturn(null, workOrder);
        when(mapper.selectWorkOrder(7L)).thenReturn(workOrder);
        when(mapper.selectWorkOrderTasks(7L,0)).thenReturn(List.of());
        when(mapper.insertAudit(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        mvc.perform(post("/api/production/work-orders").with(manager()).contentType("application/json")
            .content("{\"requestId\":\"work-order-request-0001\",\"skuId\":1,\"batchNo\":\"BATCH-20261015\",\"plannedQuantity\":10000}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.plannedQuantity").value(10000));
        verify(mapper).insertWorkOrder(eq("work-order-request-0001"), startsWith("WO-"), any(), eq("supervisor"));

        mvc.perform(post("/api/production/tasks").with(manager()).contentType("application/json")
            .content("{\"requestId\":\"supervisor-task-001\",\"skuId\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test void supervisorCanBatchBindNamesAndGenerateEanBarcode() throws Exception {
        when(mapper.selectSku(27L)).thenReturn(Map.of("id",27L,"skuCode","SUP-001"));
        when(mapper.selectSkuNames(27L)).thenReturn(List.of(new HashMap<>(Map.of(
            "id",3L,"skuId",27L,"name","TW-EMO2-L-BLACK+SILVER","ean","6979176420209","enabled",true))));
        when(eanBarcodes.dataUrl("6979176420209")).thenReturn("data:image/png;base64,barcode");
        when(mapper.insertAudit(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        mvc.perform(get("/api/production/skus/27/names").with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("TW-EMO2-L-BLACK+SILVER"))
            .andExpect(jsonPath("$.data[0].eanBarcodeDataUrl").value("data:image/png;base64,barcode"));
        mvc.perform(put("/api/production/skus/27/names").with(manager()).contentType("application/json")
            .content("{\"names\":[{\"name\":\"TW-EMO2-XL-BLACK+SILVER\",\"ean\":\"6979176420209\",\"sn\":\"SN-001\",\"enabled\":true}]}"))
            .andExpect(status().isOk());
        verify(mapper).insertSkuName(eq(27L), argThat(name -> name.sn().equals("SN-001")));
        mvc.perform(get("/api/production/barcodes/ean/6979176420209").with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dataUrl").value("data:image/png;base64,barcode"));
    }

    @Test void supervisorCanResolveIssueWithAudit() throws Exception {
        when(mapper.selectIssue("issue-1")).thenReturn(Map.of("id","issue-1"));
        when(mapper.updateIssueResolved("issue-1","复检通过","supervisor")).thenReturn(1);
        when(mapper.insertAudit(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        mvc.perform(post("/api/production/issues/issue-1/resolve").with(manager())
            .contentType("application/json").content("{\"reason\":\"复检通过\"}"))
            .andExpect(status().isOk());
        verify(mapper).insertAudit("issue-1","解除生产隔离","supervisor","复检通过");
    }

    @Test void managementAloneDoesNotGrantAssemblyOrPrinterOperation() throws Exception {
        mvc.perform(post("/api/production/tasks").with(manager()).contentType("application/json")
            .content("{\"requestId\":\"supervisor-task-001\",\"skuId\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/production/prints/claim").with(manager()).contentType("application/json")
            .content("{\"terminal\":\"test-terminal\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(mapper, guard);
    }

    @Test void readOnlyAndUnrelatedRolesCannotManage() throws Exception {
        for (String permission : new String[]{"VEHICLE_READ", "ORDER_WRITE"}) {
            mvc.perform(post("/api/production/issues/issue-1/resolve")
                .with(user("reader").authorities(new SimpleGrantedAuthority("PERM_" + permission)))
                .contentType("application/json").content("{\"reason\":\"拒绝写入\"}"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(mapper, guard);
    }

    @Test void agentAndPdaCannotUseManagementEvenWhenPermissionAssigned() throws Exception {
        for (String role : new String[]{"WEB_AGENT", "PDA_OPERATOR"}) {
            mvc.perform(post("/api/production/issues/issue-1/resolve")
                .with(user("external").authorities(new SimpleGrantedAuthority("ROLE_" + role),
                    new SimpleGrantedAuthority("PERM_PRODUCTION_MANAGE")))
                .contentType("application/json").content("{\"reason\":\"越权拒绝\"}"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(mapper, guard);
    }
}
