package com.bike.server.pi;

import com.bike.server.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiFulfillmentProfileServiceImpl implements PiFulfillmentProfileService {
    private final PiFulfillmentProfileMapper mapper;
    private final PiTemplateMapper templates;
    private final PiBusinessMapper business;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final Validator validator;
    public PiFulfillmentProfileServiceImpl(PiFulfillmentProfileMapper mapper, PiTemplateMapper templates,
            PiBusinessMapper business, OperationLogMapper logs, ObjectMapper json, Validator validator) {
        this.mapper=mapper;this.templates=templates;this.business=business;this.logs=logs;this.json=json;this.validator=validator;
    }
    public List<PiFulfillmentProfile> listProfiles() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requireAnyPermission("PI_READ", "PI_WRITE", "PI_TEMPLATE_MANAGE");
        return mapper.listProfiles().stream().map(this::decode).toList();
    }
    @Transactional
    public PiFulfillmentProfile publishProfile(PiFulfillmentProfileRequest r) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_TEMPLATE_MANAGE");
        if(r==null||!validator.validate(r).isEmpty())fail("PI_PROFILE_INVALID","请补齐履约配置资料");
        var template=templates.find(r.templateCode(),r.templateVersion());
        if(template==null||!PiTemplateService.isSelectableLayout(template.layoutCode())||!r.tradeType().equals(template.tradeType()))
            fail("PI_PROFILE_TEMPLATE_INVALID","请选择匹配贸易类型的完整已发布模板");
        if(r.rules().credit()&&!r.rules().settlementRequired())fail("PI_PROFILE_INVALID","后付款业务必须核对款项结清后结案");
        if(r.rules().customerPickup()&&r.rules().viaForwarderWarehouse())fail("PI_PROFILE_INVALID","客户到仓提货不应配置我方送货代仓");
        if(new HashSet<>(r.rules().requiredDocuments()).size()!=r.rules().requiredDocuments().size())fail("PI_PROFILE_INVALID","单据要求不能重复");
        for(Long id:r.rules().warehouseIds())if(business.countEnabledWarehouse(id)!=1)fail("WAREHOUSE_NOT_FOUND","适用仓库不存在或已停用");
        try {
            if(mapper.insertProfile(r,json.writeValueAsString(r),CurrentOperator.id())!=1)fail("PI_PROFILE_SAVE_FAILED","配置发布失败");
        } catch(org.springframework.dao.DuplicateKeyException error) {
            throw new BusinessException("PI_PROFILE_VERSION_EXISTS","该配置版本已发布，请使用新版本号");
        } catch(JsonProcessingException error) {throw new IllegalStateException("履约配置序列化失败",error);}
        var saved=decode(mapper.selectVersion(r.profileCode(),r.version()));audit(saved.id(),"发布履约配置",r.reason());return saved;
    }
    @Transactional
    public void setProfileEnabled(long id,boolean enabled,String reason) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_TEMPLATE_MANAGE");
        if(reason==null||reason.isBlank()||reason.length()>500)fail("PI_PROFILE_REASON_REQUIRED","启停配置须填写原因");
        if(mapper.selectProfile(id)==null)fail("PI_PROFILE_NOT_FOUND","履约配置不存在");
        mapper.updateEnabled(id,enabled);audit(id,enabled?"启用履约配置":"停用履约配置",reason);
    }
    public PiFulfillmentProfile resolveProfile(PiDocumentSnapshot d) {
        if(d==null||d.business()==null||d.business().fulfillmentProfileId()==null)return null;
        var row=mapper.selectProfile(d.business().fulfillmentProfileId());
        if(row==null)fail("PI_PROFILE_NOT_FOUND","本单引用的履约配置不存在，请核对");
        return decode(row);
    }
    public void validateSelection(PiDocumentSnapshot d,String tradeType,boolean newSelection) {
        var p=resolveProfile(d);
        if(p==null) {
            if(newSelection)fail("PI_PROFILE_REQUIRED","新建PI必须选择已发布的履约配置");
            return;
        }
        if(newSelection&&!p.enabled())fail("PI_PROFILE_DISABLED","该履约配置已停用，请选择可用版本");
        if(!Objects.equals(p.tradeType(),tradeType)||!Objects.equals(p.collectionEntity(),d.business().collectionEntity())
                ||!Objects.equals(p.templateCode(),d.templateCode())||!Objects.equals(p.templateVersion(),d.templateVersion()))
            fail("PI_PROFILE_MISMATCH","贸易类型、收款主体和PI模板须与所选配置版本一致");
        if(!p.rules().warehouseIds().isEmpty()&&!p.rules().warehouseIds().contains(d.business().warehouseId()))
            fail("PI_PROFILE_WAREHOUSE_MISMATCH","出库仓库不在此配置的适用范围");
        if(p.rules().credit()&&d.business().paymentDueDate()==null)fail("PI_PAYMENT_DUE_REQUIRED","后付款订单须明确付款到期日");
    }
    private PiFulfillmentProfile decode(PiFulfillmentProfileMapper.StoredProfile row) {
        try {var r=json.readValue(row.profileJson(),PiFulfillmentProfileRequest.class);
            return new PiFulfillmentProfile(row.id(),r.profileCode(),r.version(),r.name(),r.tradeType(),r.collectionEntity(),r.templateCode(),r.templateVersion(),r.rules(),row.enabled(),row.createdAt());
        } catch(JsonProcessingException error){throw new IllegalStateException("履约配置读取失败",error);}
    }
    private void audit(long id,String action,String reason) {
        if(logs.insert("PI_PROFILE",Long.toString(id),action,CurrentOperator.id(),CurrentOperator.username(),null,null,CurrentOperator.clientIp(),reason)!=1)
            fail("PI_PROFILE_AUDIT_FAILED","配置审计保存失败");
    }
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
