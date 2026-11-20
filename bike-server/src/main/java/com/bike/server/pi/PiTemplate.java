package com.bike.server.pi;

import java.time.LocalDateTime;

/** Published versions are append-only; the API has no edit or delete operation. */
public record PiTemplate(long id, String templateCode, String templateVersion, String tradeType,
        String layoutCode, String sellerName, String sellerAddress, String sellerContact,
        String bankName, String bankAccount, String beneficiary, String publishReason,
        Long publishedBy, LocalDateTime publishedAt, String sellerCountry,
        String sellerContactName, String sellerPhone, String sellerEmail,
        String bankAddress, String swiftCode, String bankCode, String branchCode,
        String sellerRegistrationNo, String sellerVatNo) {
    /** MyBatis 按全部列映射到规范构造器。 */
    @org.apache.ibatis.annotations.AutomapConstructor
    public PiTemplate {}
    /** V59 之前的字段集合，银行地址、SWIFT、Bank/Branch Code 及卖方登记号、VAT 为空。 */
    public PiTemplate(long id, String templateCode, String templateVersion, String tradeType,
            String layoutCode, String sellerName, String sellerAddress, String sellerContact,
            String bankName, String bankAccount, String beneficiary, String publishReason,
            Long publishedBy, LocalDateTime publishedAt, String sellerCountry,
            String sellerContactName, String sellerPhone, String sellerEmail) {
        this(id, templateCode, templateVersion, tradeType, layoutCode, sellerName, sellerAddress, sellerContact, bankName, bankAccount,
                beneficiary, publishReason, publishedBy, publishedAt, sellerCountry, sellerContactName, sellerPhone, sellerEmail,
                null, null, null, null, null, null);
    }
}
