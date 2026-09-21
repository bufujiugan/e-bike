package com.bike.server.production;

import static org.assertj.core.api.Assertions.*;

import com.bike.server.common.BusinessException;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class ProductionMysqlTest {
  static UnpooledDataSource dataSource() {
    return new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),
        System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
  }
  static SqlSessionFactory factory(UnpooledDataSource source, String... resources) throws Exception {
    var config=new org.apache.ibatis.session.Configuration(new Environment("mysql-test",new SpringManagedTransactionFactory(),source));
    for(String resource:resources) try(var stream=Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
    }
    return new SqlSessionFactoryBuilder().build(config);
  }
  @Configuration @EnableTransactionManagement
  static class Transactions {}

  @Test void failureAuditCommitsDespiteOuterRollback() throws Exception {
    var source=dataSource();
    String table="test_scan_failure_"+UUID.randomUUID().toString().replace("-", "");
    String ddl;
    try(var input=Resources.getResourceAsStream("db/migration/V17__production_scan_failures.sql")) {
      ddl=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
    }
    int start=ddl.indexOf("CREATE TABLE");
    int end=ddl.indexOf(";",start);
    try(var connection=source.getConnection();var statement=connection.createStatement()) {
      statement.execute(ddl.substring(start,end).replace("bike_production_scan_failure",table));
    }
    var config=new org.apache.ibatis.session.Configuration(new Environment("mysql-test",new SpringManagedTransactionFactory(),source));
    try(var stream=Resources.getResourceAsStream("mapper/ScanFailureMapper.xml")) {
      String xml=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("bike_production_scan_failure",table);
      new XMLMapperBuilder(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),config,"scan-failure-isolated",config.getSqlFragments()).parse();
    }
    var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ScanFailureMapper.class);
    String task="MYSQL-"+UUID.randomUUID();
    try(var context=new AnnotationConfigApplicationContext()) {
      context.register(Transactions.class);
      context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
      context.registerBean(ScanFailureService.class,()->new ScanFailureService(mapper));
      context.refresh();
      var outer=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
      outer.executeWithoutResult(status->{
        context.getBean(ScanFailureService.class).record(task,new ProductionContracts.ScanRequest("UNKNOWN-CODE","MOTOR"),new BusinessException("PART_NOT_FOUND","部件未登记"));
        status.setRollbackOnly();
      });
      assertThat(mapper.count(task)).isEqualTo(1);
      var row=mapper.select(task,0).getFirst();
      assertThat(row.get("code")).isEqualTo("UNKNOWN-CODE");
      assertThat(row.get("errorCode")).isEqualTo("PART_NOT_FOUND");
    } finally {
      try(var connection=source.getConnection();var statement=connection.createStatement()) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  @Test void quarantinePaginationCanReachBeyondFiveHundredRows() throws Exception {
    var source=dataSource();
    try(var session=factory(source,"mapper/InventoryPolicyMapper.xml","mapper/ProductionMapper.xml").openSession()) {
      var connection=session.getConnection();
      try(var statement=connection.createStatement()) {
        statement.execute("CREATE TEMPORARY TABLE bike_part(id BIGINT PRIMARY KEY,part_sn VARCHAR(128),part_type VARCHAR(32),qc_status VARCHAR(32),status VARCHAR(32))");
      }
      try(var insert=connection.prepareStatement("INSERT INTO bike_part VALUES(?,?,'MOTOR','FAILED','QUARANTINED')")) {
        for(int id=1;id<=551;id++){insert.setInt(1,id);insert.setString(2,"OLD-"+id);insert.addBatch();}
        insert.executeBatch();
      }
      var mapper=session.getMapper(ProductionMapper.class);
      assertThat(mapper.countQuarantinedParts(null)).isEqualTo(551);
      var historical=mapper.selectQuarantinedParts(null,500);
      assertThat(historical).hasSize(50);
      assertThat(historical.getFirst().get("code")).isEqualTo("OLD-51");
      assertThat(mapper.selectQuarantinedParts(null,550).getFirst().get("code")).isEqualTo("OLD-1");
      assertThat(mapper.selectQuarantinedParts("OLD-551",0)).hasSize(1);
      assertThat(mapper.countQuarantinedParts("OLD-551")).isEqualTo(1);
      assertThat(mapper.selectQuarantinedParts("UNKNOWN",0)).isEmpty();
      session.rollback();
    }
  }
}
