package com.bike.server.validation;

public interface SnRuleService {
    String normalizeFrameSn(String value);

    String normalizePartSn(String value, String partType);
}
