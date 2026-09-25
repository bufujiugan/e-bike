package com.bike.server.files;

import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Separate small pool: outer upload transactions must not exhaust the connections needed to save intent. */
@Component
public class FileUploadIntentStore implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final String table;
    private final HikariDataSource owned;
    @Autowired
    public FileUploadIntentStore(DataSourceProperties properties) {
        var config=new HikariConfig();config.setJdbcUrl(properties.determineUrl());
        config.setUsername(properties.determineUsername());config.setPassword(properties.determinePassword());
        config.setDriverClassName(properties.determineDriverClassName());config.setMaximumPoolSize(2);config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);config.setPoolName("upload-intent");
        owned=new HikariDataSource(config);jdbc=new JdbcTemplate(owned);table="bike_file_upload";
    }
    FileUploadIntentStore(DataSource source,String table) {
        if(!table.matches("[a-zA-Z0-9_]+"))throw new IllegalArgumentException("Invalid test table");
        jdbc=new JdbcTemplate(source);this.table=table;owned=null;
    }
    public void begin(String key,FileUploadJournal.Context context,long size,String type,Long user,String name) {
        if(jdbc.update("INSERT INTO "+table+"(object_key,purpose,business_id,file_name,content_type,file_size,sha256,created_by,created_name) VALUES(?,?,?,?,?,?,?,?,?)",
                key,context.purpose(),context.businessId(),context.fileName(),type,size,context.sha256(),user,name)!=1)
            throw new IllegalStateException("Upload intent was not saved");
    }
    public void failed(String key) {
        jdbc.update("UPDATE "+table+" SET state='REVIEW',version=version+1,updated_at=NOW(3),error_code='UPLOAD_OR_BIND_FAILED' WHERE object_key=? AND state='PENDING'",key);
    }
    @PreDestroy public void close(){if(owned!=null)owned.close();}
}
