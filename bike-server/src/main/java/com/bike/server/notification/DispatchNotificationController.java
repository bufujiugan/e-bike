package com.bike.server.notification;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch-notifications")
public class DispatchNotificationController {
    private final DispatchNotificationService service;

    public DispatchNotificationController(DispatchNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<DispatchNotification>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                               @RequestParam(defaultValue = "1") int page) {
        return ApiResponse.success(service.list(unreadOnly, page));
    }

    @GetMapping("/pending")
    public ApiResponse<DispatchNotificationService.Pending> pending(@RequestParam(defaultValue = "0") long afterId) {
        return ApiResponse.success(service.pending(afterId));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable long id) {
        service.markRead(id);
        return ApiResponse.success("已读", null);
    }
}
