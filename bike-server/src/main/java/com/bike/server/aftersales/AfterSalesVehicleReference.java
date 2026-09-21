package com.bike.server.aftersales;

/** Only used to verify an explicitly requested link; never exposed as a vehicle lookup API. */
public record AfterSalesVehicleReference(Long id, String frameSn, Long modelId, Long customerId, Long agentId,
                                        String motorSn, String batterySn, String displaySn) {}
