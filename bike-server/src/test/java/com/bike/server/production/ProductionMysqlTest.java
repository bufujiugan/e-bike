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

  static class TaskFixture implements AutoCloseable {
    final String prefix="tasklist_"+UUID.randomUUID().toString().replace("-","")+"_";
    final org.apache.ibatis.session.SqlSession session;
    final ProductionMapper mapper;
    final Connection connection;
    TaskFixture() throws Exception {
      var config=new org.apache.ibatis.session.Configuration(new Environment("task-list",new SpringManagedTransactionFactory(),dataSource()));
      for(String resource:java.util.List.of("mapper/InventoryPolicyMapper.xml","mapper/ProductionMapper.xml"))try(var stream=Resources.getResourceAsStream(resource)) {
        var xml=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("bike_",prefix);
        new XMLMapperBuilder(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),config,resource,config.getSqlFragments()).parse();
      }
      session=new SqlSessionFactoryBuilder().build(config).openSession();connection=session.getConnection();mapper=session.getMapper(ProductionMapper.class);
      execute("CREATE TABLE "+prefix+"production_sku(id BIGINT PRIMARY KEY,sku_code VARCHAR(64),product_name VARCHAR(128))");
      execute("CREATE TABLE "+prefix+"vehicle(id BIGINT PRIMARY KEY,frame_sn VARCHAR(128),vehicle_qr_code VARCHAR(255))");
      execute(ProductionFixtureSql.statement("createTask", prefix));
      execute("CREATE TABLE "+prefix+"production_scan(task_id VARCHAR(64),code_type VARCHAR(20),code VARCHAR(128),active TINYINT,UNIQUE KEY(task_id,code_type))");
      execute("INSERT INTO "+prefix+"production_sku VALUES(1,'SKU-TEST','商品规格测试')");
    }
    void execute(String sql) throws Exception {try(var statement=connection.createStatement()){statement.execute(sql);}}
    void task(String id,String state,String updated,Long vehicle) throws Exception {
      try(var insert=connection.prepareStatement(ProductionFixtureSql.statement("insertTask", prefix))) {
        insert.setString(1,id);insert.setString(2,state);insert.setObject(3,vehicle);insert.setTimestamp(4,Timestamp.valueOf(updated));insert.executeUpdate();
      }
    }
    void scan(String id,String type,String code,int active) throws Exception {
      try(var insert=connection.prepareStatement("INSERT INTO "+prefix+"production_scan VALUES(?,?,?,?)")) {
        insert.setString(1,id);insert.setString(2,type);insert.setString(3,code);insert.setInt(4,active);insert.executeUpdate();
      }
    }
    public void close() throws Exception {
      try {for(String table:java.util.List.of("production_scan","production_task","vehicle","production_sku"))execute("DROP TABLE "+prefix+table);}
      finally {session.close();}
    }
  }

  @Test void taskListsSortByCreationAndIdAcrossPagesAndFilters() throws Exception {
    try(var f=new TaskFixture()) {
      for(int i=0;i<51;i++)f.task("completed-%02d".formatted(i),"COMPLETED",i==50?"2026-09-02 00:00:00":"2026-09-01 00:00:00",null);
      f.task("open-old","OPEN","2026-09-24 00:00:00",null);
      f.task("open-new","OPEN","2026-09-25 00:00:00",null);
      f.task("cancelled","CANCELLED","2026-09-26 00:00:00",null);
      var first=f.mapper.selectTasks(null,0);
      assertThat(first).hasSize(50);
      assertThat(first.getFirst().get("id")).isEqualTo("open-old");
      assertThat(first.getLast().get("id")).isEqualTo("completed-03");
      assertThat(f.mapper.selectTasks(null,50)).extracting(row->row.get("id"))
          .containsExactly("completed-02","completed-01","completed-00","cancelled");
      assertThat(f.mapper.selectTasksFiltered(null,0,null,null,null,null)).isEqualTo(first);
      assertThat(f.mapper.selectTasksFiltered("BATCH-TEST",50,1L,null,java.time.LocalDate.of(2026,9,1),java.time.LocalDate.of(2026,9,1)))
          .extracting(row->row.get("id")).containsExactly("completed-02","completed-01","completed-00","cancelled");
      assertThat(f.mapper.selectTasksFiltered(null,0,1L,"OPEN",null,null)).extracting(row->row.get("id")).containsExactly("open-old","open-new");
      assertThat(f.mapper.countTasks(null)).isEqualTo(54);
      assertThat(f.mapper.countTasksFiltered(null,1L,"OPEN",null,null)).isEqualTo(2);
      assertThat(f.mapper.selectTasksFiltered(null,0,2L,null,null,null)).isEmpty();
      f.execute("UPDATE "+f.prefix+"production_task SET updated_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE status='OPEN'");
      assertThat(f.mapper.touchTask("open-new")).isEqualTo(1);
      assertThat(f.mapper.selectTasksFiltered(null,0,null,"OPEN",null,null).getFirst().get("id")).isEqualTo("open-old");
    }
  }

  @Test void taskCodesIncludePartialScansAndRetainReleasedScanHistory() throws Exception {
    try(var f=new TaskFixture()) {
      f.execute("INSERT INTO "+f.prefix+"vehicle VALUES(1,'FRAME-DONE','BIKE:1:AGGREGATE')");
      f.task("done","COMPLETED","2026-09-01 00:00:00",1L);
      for(String type:java.util.List.of("FRAME","MOTOR","BATTERY","DISPLAY"))f.scan("done",type,type+"-DONE",0);
      f.task("partial","OPEN","2026-09-02 00:00:00",null);
      f.scan("partial","FRAME","FRAME-PARTIAL",1);f.scan("partial","MOTOR","MOTOR-PARTIAL",1);
      f.task("cancelled","CANCELLED","2026-09-03 00:00:00",null);
      f.scan("cancelled","BATTERY","BATTERY-CANCELLED",0);
      var done=f.mapper.selectTask("done");
      assertThat(done).containsEntry("qrCode","BIKE:1:AGGREGATE").containsEntry("frameSn","FRAME-DONE")
          .containsEntry("motorSn","MOTOR-DONE").containsEntry("batterySn","BATTERY-DONE").containsEntry("displaySn","DISPLAY-DONE");
      var partial=f.mapper.selectTasksFiltered(null,0,null,"OPEN",null,null).getFirst();
      assertThat(partial).containsEntry("frameSn","FRAME-PARTIAL").containsEntry("motorSn","MOTOR-PARTIAL");
      assertThat(partial.get("qrCode")).isNull();assertThat(partial.get("batterySn")).isNull();assertThat(partial.get("displaySn")).isNull();
      assertThat(f.mapper.selectTasks("FRAME-PARTIAL",0)).hasSize(1);
      assertThat(f.mapper.countTasksFiltered("FRAME-PARTIAL",null,null,null,null)).isEqualTo(1);
      assertThat(f.mapper.selectTask("cancelled")).containsEntry("batterySn","BATTERY-CANCELLED");
      assertThat(f.mapper.selectTasks("BIKE:1:AGGREGATE",0)).extracting(row->row.get("id")).containsExactly("done");
      assertThat(f.mapper.countTasks("BIKE:1:AGGREGATE")).isEqualTo(1);
      assertThat(f.mapper.selectTasksFiltered("AGGREGATE",0,1L,"COMPLETED",java.time.LocalDate.of(2026,9,1),java.time.LocalDate.of(2026,9,1)))
          .extracting(row->row.get("id")).containsExactly("done");
      assertThat(f.mapper.countTasksFiltered("AGGREGATE",1L,"COMPLETED",java.time.LocalDate.of(2026,9,1),java.time.LocalDate.of(2026,9,1))).isEqualTo(1);
      assertThat(f.mapper.countTasksFiltered("BIKE:1:AGGREGATE",null,"OPEN",null,null)).isZero();
      assertThat(f.mapper.selectTasksFiltered("BIKE:missing",0,null,null,null,null)).isEmpty();
    }
  }

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
      statement.execute(ProductionFixtureSql.statement("addFailureUpdatedAt", "bike_")
          .replace("bike_production_scan_failure", table));
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
        statement.execute(ProductionFixtureSql.statement("createQuarantinedParts", "bike_"));
      }
      try(var insert=connection.prepareStatement(ProductionFixtureSql.statement("insertQuarantinedPart", "bike_"))) {
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
