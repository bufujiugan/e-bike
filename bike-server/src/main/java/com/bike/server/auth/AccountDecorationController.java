package com.bike.server.auth;

import com.bike.server.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth/decoration")
public class AccountDecorationController {
    private final AccountDecorationService decorationService;

    public AccountDecorationController(AccountDecorationService decorationService) {
        this.decorationService = decorationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AccountDecorationView>> getCurrentDecoration() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(decorationService.getCurrentDecoration()));
    }

    @PutMapping(consumes = "multipart/form-data")
    public ApiResponse<AccountDecorationView> updateCurrentDecoration(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success("装饰图片已更新", decorationService.updateCurrentDecoration(file));
    }

    @DeleteMapping
    public ApiResponse<AccountDecorationView> resetCurrentDecoration() {
        return ApiResponse.success("已恢复默认装饰图片", decorationService.resetCurrentDecoration());
    }
}
