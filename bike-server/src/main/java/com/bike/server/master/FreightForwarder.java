package com.bike.server.master;

public record FreightForwarder(
        long id, String name, String contactName, String phone, String address, String remark, int revision) {
}
