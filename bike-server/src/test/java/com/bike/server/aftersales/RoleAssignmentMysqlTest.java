package com.bike.server.aftersales;

import com.bike.server.system.*;
import com.bike.server.common.OperationLogMapper;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;

/** Real transaction annotations matter: REPEATABLE READ empty-range deletes reproduce the production deadlock. */
@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class RoleAssignmentMysqlTest {
    record Mappers(UserMapper users,RoleMapper roles,OperationLogMapper logs) {}
    static Mappers mappers(AfterSalesMysqlTest.Fixture f)throws Exception {
        f.sql("ALTER TABLE "+f.prefix+"sys_user ADD COLUMN workstation_id BIGINT NULL,ADD COLUMN must_change_password TINYINT DEFAULT 0,ADD COLUMN last_login_at DATETIME NULL,ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP,ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        f.sql("ALTER TABLE "+f.prefix+"sys_role ADD COLUMN role_name VARCHAR(100),ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        f.sql("ALTER TABLE "+f.prefix+"operation_log ADD COLUMN remark VARCHAR(1000)");
        f.sql("ALTER TABLE "+f.prefix+"sys_permission ADD COLUMN permission_name VARCHAR(100),ADD COLUMN permission_type VARCHAR(32)");
        f.sql("INSERT INTO "+f.prefix+"sys_user(id,username,display_name,user_type,enabled) VALUES(200,'parallel-a','A','WEB_OPERATOR',1),(201,'parallel-b','B','WEB_OPERATOR',1)");
        f.sql("INSERT INTO "+f.prefix+"sys_role(id,role_code,role_name,enabled) VALUES(200,'PARALLEL_A','A',1),(201,'PARALLEL_B','B',1)");
        var config=new Configuration(new Environment("role-concurrency",new SpringManagedTransactionFactory(),f.source));config.setMapUnderscoreToCamelCase(true);
        for(String name:List.of("UserMapper","RoleMapper","OperationLogMapper"))try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
            byte[] xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",f.prefix).getBytes(StandardCharsets.UTF_8);
            new XMLMapperBuilder(new ByteArrayInputStream(xml),config,name,config.getSqlFragments()).parse();
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));return new Mappers(session.getMapper(UserMapper.class),session.getMapper(RoleMapper.class),session.getMapper(OperationLogMapper.class));
    }
    @SuppressWarnings("unchecked") static <T> T transactional(AfterSalesMysqlTest.Fixture f,T service) {
        var factory=new ProxyFactory(service);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(f.source),new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();
    }
    static <T> T admin(Supplier<T> action){login("WEB_ADMIN",null,"ROLE_SUPER_ADMIN");try{return action.get();}finally{SecurityContextHolder.clearContext();}}
    @SuppressWarnings("unchecked") static <T> T intercept(Class<T> type,T mapper,String name,CyclicBarrier barrier,boolean failAudit) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(self,method,args)->{
            if(failAudit&&method.getName().equals("insertOperationLog"))return 0;
            try {var result=method.invoke(mapper,args);if(method.getName().equals(name))barrier.await(5,TimeUnit.SECONDS);return result;}catch(InvocationTargetException e){throw e.getCause();}
        });
    }

    @Test void assigningRolesToDifferentNewUsersDoesNotDeadlockAfterBothEmptyDeletes()throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var m=mappers(f);var barrier=new CyclicBarrier(2);var mapper=intercept(UserMapper.class,m.users(),"clearRoles",barrier,false);
            var service=transactional(f,new UserServiceImpl(mapper,new BCryptPasswordEncoder()));
            var a=pool.submit(()->admin(()->service.updateRoles(200,new UserRoleRequest(List.of(1L)))));
            var b=pool.submit(()->admin(()->service.updateRoles(201,new UserRoleRequest(List.of(2L)))));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertThat(m.users().selectRoleIds(200)).containsExactly(1L);assertThat(m.users().selectRoleIds(201)).containsExactly(2L);
            assertThat(f.count("operation_log")).isEqualTo(2);
        }
    }

    @Test void assigningPermissionsToDifferentNewRolesUsesTheSameSafeTransactionBoundary()throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var m=mappers(f);var mapper=intercept(RoleMapper.class,m.roles(),"clearPermissions",new CyclicBarrier(2),false);
            var service=transactional(f,new RoleServiceImpl(mapper,m.logs()));
            var a=pool.submit(()->admin(()->service.updatePermissions(200,new RolePermissionRequest(List.of(1L)))));
            var b=pool.submit(()->admin(()->service.updatePermissions(201,new RolePermissionRequest(List.of(2L)))));
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertThat(m.roles().selectPermissions(200)).extracting(PermissionView::id).containsExactly(1L);assertThat(m.roles().selectPermissions(201)).extracting(PermissionView::id).containsExactly(2L);
        }
    }

    @Test void replacingSameUserRolesIsSerializedAndAuditFailureRollsBackWholeReplacement()throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var m=mappers(f);var service=transactional(f,new UserServiceImpl(m.users(),new BCryptPasswordEncoder()));var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return admin(()->service.updateRoles(200,new UserRoleRequest(List.of(1L,2L))));});
            var b=pool.submit(()->{start.await();return admin(()->service.updateRoles(200,new UserRoleRequest(List.of(3L))));});start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            var before=m.users().selectRoleIds(200);assertThat(before).isIn(List.of(1L,2L),List.of(3L));
            var broken=transactional(f,new UserServiceImpl(intercept(UserMapper.class,m.users(),"unused",null,true),new BCryptPasswordEncoder()));
            assertCode("ROLE_UPDATE_FAILED",()->admin(()->broken.updateRoles(200,new UserRoleRequest(List.of(4L)))));
            assertThat(m.users().selectRoleIds(200)).isEqualTo(before);assertThat(f.count("operation_log")).isEqualTo(2);
        }
    }
}
