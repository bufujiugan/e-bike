package com.bike.server.master;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FreightForwarderMapper {
    List<FreightForwarder> listForwarders();

    FreightForwarder selectForwarder(@Param("id") long id);

    int insertForwarder(@Param("forwarder") FreightForwarderRequest forwarder);

    long selectInsertedId();

    int updateForwarder(@Param("id") long id, @Param("revision") int revision,
                        @Param("forwarder") FreightForwarderRequest forwarder);
}
