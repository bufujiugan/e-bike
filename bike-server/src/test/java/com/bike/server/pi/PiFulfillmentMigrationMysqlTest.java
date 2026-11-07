package com.bike.server.pi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BIKE_FULFILLMENT_MIGRATION_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3337/bike_unified_migration_test.*")
class PiFulfillmentMigrationMysqlTest {
    @Test void migratesCompleteSchemaAndAcceptsCreditReservationMode() throws Exception {
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_FULFILLMENT_MIGRATION_URL"),"root","");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .javaMigrations(new com.bike.server.production.migration.V16__production_workflow(),new com.bike.server.production.migration.V57__unified_fulfillment())
            .table("bike_schema_history").load();
        flyway.migrate();flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("57");
        var configuration=new Configuration(new Environment("migration",new JdbcTransactionFactory(),source));
        try(var input=Resources.getResourceAsStream("mapper/PiFulfillmentLocalFixtureMapper.xml")) {
            new XMLMapperBuilder(input,configuration,"PiFulfillmentLocalFixtureMapper",configuration.getSqlFragments()).parse();
        }
        try(var session=new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            assertThat((Integer)session.selectOne("piFulfillmentLocalFixture.creditConstraintCount")).isEqualTo(1);
        }
    }
}
