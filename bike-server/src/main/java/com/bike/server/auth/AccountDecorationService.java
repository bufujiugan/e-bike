package com.bike.server.auth;

import org.springframework.web.multipart.MultipartFile;

public interface AccountDecorationService {
    AccountDecorationView getCurrentDecoration();
    AccountDecorationView updateCurrentDecoration(MultipartFile file);
    AccountDecorationView resetCurrentDecoration();
}
