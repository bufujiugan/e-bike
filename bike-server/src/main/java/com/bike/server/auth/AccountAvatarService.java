package com.bike.server.auth;

import org.springframework.web.multipart.MultipartFile;

public interface AccountAvatarService {
    AccountAvatarView getCurrentAvatar();
    AccountAvatarView updateCurrentAvatar(MultipartFile file);
    AccountAvatarView resetCurrentAvatar();
}
