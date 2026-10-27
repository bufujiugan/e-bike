package com.bike.server.auth;

import com.bike.server.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth/avatar")
public class AccountAvatarController {
    private final AccountAvatarService avatarService;

    public AccountAvatarController(AccountAvatarService avatarService) {
        this.avatarService = avatarService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AccountAvatarView>> getCurrentAvatar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(avatarService.getCurrentAvatar()));
    }

    @PutMapping(consumes = "multipart/form-data")
    public ApiResponse<AccountAvatarView> updateCurrentAvatar(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success("头像已更新", avatarService.updateCurrentAvatar(file));
    }

    @DeleteMapping
    public ApiResponse<AccountAvatarView> resetCurrentAvatar() {
        return ApiResponse.success("已恢复默认头像", avatarService.resetCurrentAvatar());
    }
}
