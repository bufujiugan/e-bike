package com.bike.server.files;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FileUploadMapper {
    int insert(@Param("key") String key, @Param("context") FileUploadJournal.Context context,
            @Param("size") long size, @Param("type") String type, @Param("user") Long user, @Param("name") String name);
    FileUploadRecord get(@Param("key") String key);
    FileUploadRecord lock(@Param("key") String key);
    int references(@Param("key") String key);
    int bound(@Param("key") String key);
    int failed(@Param("key") String key);
    int checked(@Param("key") String key, @Param("state") String state, @Param("objectState") String objectState,
            @Param("error") String error);
    int review(@Param("key") String key, @Param("state") String state, @Param("reason") String reason, @Param("user") Long user);
    List<FileUploadRecord> list(@Param("state") String state, @Param("offset") int offset, @Param("limit") int limit);
    long count(@Param("state") String state);
    List<String> pending();
    int event(@Param("row") FileUploadRecord row, @Param("action") String action, @Param("state") String state,
            @Param("reason") String reason, @Param("user") Long user, @Param("name") String name);
    List<java.util.Map<String,Object>> history(@Param("key") String key);
}
