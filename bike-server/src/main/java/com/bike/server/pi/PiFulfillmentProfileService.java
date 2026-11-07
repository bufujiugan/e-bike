package com.bike.server.pi;

import java.util.List;

public interface PiFulfillmentProfileService {
    List<PiFulfillmentProfile> listProfiles();
    PiFulfillmentProfile publishProfile(PiFulfillmentProfileRequest request);
    void setProfileEnabled(long id, boolean enabled, String reason);
    PiFulfillmentProfile resolveProfile(PiDocumentSnapshot document);
    void validateSelection(PiDocumentSnapshot document, String tradeType, boolean newSelection);
}
