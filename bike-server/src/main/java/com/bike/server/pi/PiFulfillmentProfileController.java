package com.bike.server.pi;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pi-fulfillment-profiles")
public class PiFulfillmentProfileController {
    private final PiFulfillmentProfileService service;
    public PiFulfillmentProfileController(PiFulfillmentProfileService service){this.service=service;}
    @GetMapping public ApiResponse<List<PiFulfillmentProfile>> list(){return ApiResponse.success("查询成功",service.listProfiles());}
    @PostMapping public ApiResponse<PiFulfillmentProfile> publish(@Valid @RequestBody PiFulfillmentProfileRequest request){return ApiResponse.success("配置版本已发布",service.publishProfile(request));}
    public record Availability(boolean enabled,@NotBlank @Size(max=500) String reason){}
    @PutMapping("/{id}/availability") public ApiResponse<Void> availability(@PathVariable long id,@Valid @RequestBody Availability request){
        service.setProfileEnabled(id,request.enabled(),request.reason());return ApiResponse.success("配置状态已更新",null);
    }
}
