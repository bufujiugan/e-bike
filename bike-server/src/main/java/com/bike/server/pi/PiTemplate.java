package com.bike.server.pi;

import java.time.LocalDateTime;

/** Published versions are append-only; the API has no edit or delete operation. */
public record PiTemplate(long id, String templateCode, String templateVersion, String tradeType,
        String layoutCode, String sellerName, String sellerAddress, String sellerContact,
        String bankName, String bankAccount, String beneficiary, String publishReason,
        Long publishedBy, LocalDateTime publishedAt, String sellerCountry,
        String sellerContactName, String sellerPhone, String sellerEmail) {}
