package com.bike.server.pi;

public interface PiWorkflowService {
    PiWorkflowView get(long piId);
    PiWorkflowView complete(long piId, String stepCode, PiWorkflowRequest request);
    PiWorkflowView recordProductionUpdate(long piId, PiWorkflowRequest request);
}
