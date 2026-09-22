package com.bike.server.pi;

import org.apache.ibatis.annotations.*;

@Mapper
public interface PiExportMapper {
    int enqueue(@Param("piId") long piId,@Param("snapshot") String snapshot,@Param("sourceType") String sourceType);
    PiExportJob get(@Param("piId") long piId);
    Long next();
    int claim(@Param("piId") long piId,@Param("token") String token);
    int saveFile(@Param("piId") long piId,@Param("token") String token,@Param("format") String format,
            @Param("key") String key,@Param("hash") String hash,@Param("size") long size);
    int ready(@Param("piId") long piId,@Param("token") String token);
    int fail(@Param("piId") long piId,@Param("token") String token,@Param("code") String code);
    int retry(@Param("piId") long piId,@Param("revision") long revision);
}
