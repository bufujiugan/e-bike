package com.bike.server;

import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.assertThat;

class MapperResourcesTest {
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
