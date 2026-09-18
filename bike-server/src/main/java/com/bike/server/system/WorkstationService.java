package com.bike.server.system;
import java.util.List;
public interface WorkstationService { List<WorkstationView> list(String keyword); WorkstationView create(WorkstationRequest request); WorkstationView update(long id,WorkstationRequest request); WorkstationView updateStatus(long id,boolean enabled); }
