package com.bike.server.master;

import java.util.List;

public interface MasterService {
    List<MasterItem> list(String type, String keyword);
    MasterItem create(String type, MasterCreateRequest request);
    MasterItem update(String type, long id, MasterUpdateRequest request);
    MasterItem updateStatus(String type, long id, boolean enabled);
    java.util.List<LocationItem> listLocations(long warehouseId);
    LocationItem createLocation(long warehouseId, MasterCreateRequest request);
    LocationItem updateLocation(long id, LocationUpdateRequest request);
    LocationItem updateLocationStatus(long id, boolean enabled);
}
