package com.bike.server.agent;

import com.bike.server.common.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelAccessService {
    private final AgentModelAccessMapper mapper;
    private final OperationLogMapper logs;
    public AgentModelAccessService(AgentModelAccessMapper mapper,OperationLogMapper logs){this.mapper=mapper;this.logs=logs;}
    public List<Long> get(long agentId){
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requireRole("SUPER_ADMIN");
        return mapper.selectModels(agentId);
    }
    @Transactional
    public List<Long> replace(long agentId,List<Long> models){
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requireRole("SUPER_ADMIN");
        if(models==null||models.size()>1000||models.stream().anyMatch(id->id==null||id<=0)||models.stream().distinct().count()!=models.size())
            throw new BusinessException("INVALID_PARAMETER","车型列表非法、重复或超过1000项");
        if(mapper.lockAgent(agentId)==null)throw new BusinessException("AGENT_NOT_FOUND","代理商不存在");
        if(!models.isEmpty()&&mapper.countEnabledModels(models)!=models.size())throw new BusinessException("MODEL_NOT_FOUND","车型不存在或已停用");
        List<Long> before=mapper.selectModels(agentId);
        mapper.disableAll(agentId);
        models.forEach(model->mapper.grant(agentId,model));
        logs.insert("PERMISSION_CHANGE",String.valueOf(agentId),"更新代理商车型授权",CurrentOperator.id(),CurrentOperator.username(),
            "{\"modelIds\":"+before+"}","{\"modelIds\":"+models+"}",CurrentOperator.clientIp(),null);
        return mapper.selectModels(agentId);
    }
}
