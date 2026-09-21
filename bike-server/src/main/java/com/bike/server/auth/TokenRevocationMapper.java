package com.bike.server.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TokenRevocationMapper {
    int countRevoked(@Param("tokenId") String tokenId);
    int revoke(@Param("tokenId") String tokenId, @Param("expiresAt") java.time.Instant expiresAt);
    int deleteExpired();
}
