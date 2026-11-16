package com.bike.server.pi;

/** 新单明确选择简化办理；历史快照未带此标志时保留原规则。 */
public final class PiManualWorkflow {
    private PiManualWorkflow() {}
    public static boolean enabled(PiDocumentSnapshot document) {
        return document!=null && document.business()!=null && "USER_CONFIRMED".equals(document.business().workflowMode());
    }
}
