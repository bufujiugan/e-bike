package com.bike.server.order;

/** 同一SQL语句、同一数据库时刻的库存承诺快照。 */
public class OrderInventorySnapshot {
    private long physicalQty, committedQty, ownedQty, reservationCount;
    public long getPhysicalQty(){return physicalQty;}
    public void setPhysicalQty(long value){physicalQty=value;}
    public long getCommittedQty(){return committedQty;}
    public void setCommittedQty(long value){committedQty=value;}
    public long getOwnedQty(){return ownedQty;}
    public void setOwnedQty(long value){ownedQty=value;}
    public long getReservationCount(){return reservationCount;}
    public void setReservationCount(long value){reservationCount=value;}
    public long availableForOrder(){return physicalQty-committedQty+ownedQty;}
}
