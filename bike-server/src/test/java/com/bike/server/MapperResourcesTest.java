package com.bike.server;

import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.assertThat;

class MapperResourcesTest {
    @Test void contractScenarioJsonColumnsExistInPiSchema() throws Exception {
        var config = new MybatisConfiguration();
        var resolver = new PathMatchingResourcePatternResolver();
        var resource = resolver.getResource("classpath:mapper/OrderContractMapper.xml");
        try (var stream = resource.getInputStream()) {
            new MybatisXMLMapperBuilder(stream, config, resource.toString(), config.getSqlFragments()).parse();
        }
        String schema;
        try (var stream = resolver.getResource("classpath:db/migration/V24__pi_multi_item_and_fulfillment.sql").getInputStream()) {
            schema = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String piDefinition = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS bike_pi ("), schema.indexOf(") ENGINE="));
        var columns = java.util.regex.Pattern.compile("(?m)^\\s+(\\w+)\\s+JSON\\b").matcher(piDefinition);
        var jsonColumns = new java.util.HashSet<String>();
        while (columns.find()) jsonColumns.add(columns.group(1));
        assertThat(jsonColumns).isNotEmpty();
        String query = config.getMappedStatement("com.bike.server.order.OrderContractMapper.selectLinkedPiScenarios")
                .getBoundSql(java.util.Map.of("orderId", 1L)).getSql();
        var references = java.util.regex.Pattern.compile("JSON_EXTRACT\\(p\\.(\\w+)").matcher(query);
        var referencedColumns = new java.util.ArrayList<String>();
        while (references.find()) referencedColumns.add(references.group(1));
        // 校验真实表字段，防止把 PiView 的查询别名误用于其他 Mapper。
        assertThat(referencedColumns).isNotEmpty().allSatisfy(column -> assertThat(jsonColumns).contains(column));
    }

    @Test void allProductionMappersAndSharedFragmentsParse() throws Exception {
        var resources=new PathMatchingResourcePatternResolver().getResources("file:src/main/resources/mapper/*.xml");
        assertThat(resources).isNotEmpty();
        var config=new MybatisConfiguration();
        for(var resource:resources)try(var stream=resource.getInputStream()) {
            new MybatisXMLMapperBuilder(stream,config,resource.toString(),config.getSqlFragments()).parse();
        }
        assertThat(config.getMappedStatementNames()).isNotEmpty();
    }
}
