package com.bike.server.pi;

import java.util.List;
import java.util.Map;

public record PiOptionsView(
        List<Map<String, Object>> customers, List<Map<String, Object>> agents,
        List<Map<String, Object>> skus, List<Map<String, Object>> orders) {}
