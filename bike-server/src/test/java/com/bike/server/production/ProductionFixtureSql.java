package com.bike.server.production;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.ibatis.io.Resources;

/** 独立测试表使用当前查询依赖的字段，新增 SQL 统一维护在测试 Mapper XML。 */
final class ProductionFixtureSql {
  private ProductionFixtureSql() {}

  static String statement(String id, String prefix) throws IOException {
    if (!prefix.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("测试表前缀无效");
    try (var input = Resources.getResourceAsStream("mapper/ProductionRegressionFixtureMapper.xml")) {
      var xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      var match = Pattern.compile("<(?:update|insert) id=\"" + Pattern.quote(id)
          + "\">(.*?)</(?:update|insert)>", Pattern.DOTALL).matcher(xml);
      if (!match.find()) throw new IllegalArgumentException("测试 SQL 不存在：" + id);
      return match.group(1).replace("bike_", prefix);
    }
  }
}
