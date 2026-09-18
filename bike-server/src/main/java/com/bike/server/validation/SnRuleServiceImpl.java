package com.bike.server.validation;

import com.bike.server.common.BusinessException;
import com.bike.server.system.SystemConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class SnRuleServiceImpl implements SnRuleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SnRuleServiceImpl.class);
    private static final String DEFAULT_REGEX = "^[A-Z0-9][A-Z0-9_-]{2,127}$";
    private static final int MAX_DATABASE_LENGTH = 128;
    private final SystemConfigMapper configMapper;

    public SnRuleServiceImpl(SystemConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    @Override
    public String normalizeFrameSn(String value) {
        return validate(value, "frame", null, "车架 SN");
    }

    @Override
    public String normalizePartSn(String value, String partType) {
        String normalizedType = partType == null ? "" : partType.trim().toUpperCase(Locale.ROOT);
        return validate(value, "part", normalizedType, "零部件 SN");
    }

    private String validate(String value, String category, String partType, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("INVALID_SN_FORMAT", label + "不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        int minLength = readInt("sn." + category + ".min-length", 3, 1, MAX_DATABASE_LENGTH);
        int maxLength = readInt("sn." + category + ".max-length", MAX_DATABASE_LENGTH, minLength, MAX_DATABASE_LENGTH);
        if (normalized.length() < minLength || normalized.length() > maxLength) {
            throw new BusinessException("INVALID_SN_FORMAT", label + "长度不符合系统规则");
        }
        String regex = readValue(partType == null || partType.isBlank()
                ? "sn." + category + ".regex"
                : "sn." + category + "." + partType + ".regex");
        if (regex == null) regex = readValue("sn." + category + ".regex");
        if (regex == null || regex.isBlank()) regex = DEFAULT_REGEX;
        try {
            if (!Pattern.compile(regex).matcher(normalized).matches()) {
                throw new BusinessException("INVALID_SN_FORMAT", label + "格式不符合系统规则");
            }
        } catch (PatternSyntaxException exception) {
            LOGGER.warn("SN 格式配置无效，使用默认规则，configKey={}",
                    partType == null || partType.isBlank() ? "sn." + category + ".regex" : "sn." + category + "." + partType + ".regex", exception);
            if (!Pattern.compile(DEFAULT_REGEX).matcher(normalized).matches()) {
                throw new BusinessException("INVALID_SN_FORMAT", label + "格式不符合系统规则");
            }
        }
        String prefix = readValue(partType == null || partType.isBlank()
                ? "sn." + category + ".prefix"
                : "sn." + category + "." + partType + ".prefix");
        if (prefix == null) prefix = readValue("sn." + category + ".prefix");
        if (prefix != null && !prefix.isBlank()) {
            boolean matches = java.util.Arrays.stream(prefix.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .anyMatch(normalized::startsWith);
            if (!matches) throw new BusinessException("INVALID_SN_FORMAT", label + "前缀不符合系统规则");
        }
        return normalized;
    }

    private String readValue(String key) {
        if (configMapper == null) return null;
        try {
            return configMapper.selectValue(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("读取 SN 校验配置失败，使用默认规则，configKey={}", key, exception);
            return null;
        }
    }

    private int readInt(String key, int fallback, int min, int max) {
        String value = readValue(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            LOGGER.warn("SN 长度配置无效，使用默认值，configKey={}", key, exception);
            return fallback;
        }
    }
}
