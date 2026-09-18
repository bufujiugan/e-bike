package com.bike.server.system;

import java.util.List;

public interface SystemConfigService {
    List<SystemConfigView> list();
    SystemConfigView get(String configKey);
    SystemConfigView update(String configKey, SystemConfigRequest request);
}
