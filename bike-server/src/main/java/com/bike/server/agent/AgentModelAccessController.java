package com.bike.server.agent;

import com.bike.server.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/master/agents/{agentId}/model-access")
public class AgentModelAccessController {
    private final AgentModelAccessService service;
    public AgentModelAccessController(AgentModelAccessService service){this.service=service;}
    public record Request(List<Long> modelIds) {}
    @GetMapping public ApiResponse<List<Long>> get(@PathVariable long agentId){return ApiResponse.success("查询成功",service.get(agentId));}
    @PutMapping public ApiResponse<List<Long>> replace(@PathVariable long agentId,@RequestBody Request request){return ApiResponse.success("授权已更新",service.replace(agentId,request.modelIds()));}
}
