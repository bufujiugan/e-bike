package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Invoked immediately after the source audit insert, within the same business transaction. */
final class AfterSalesNotifications {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final Map<String,String> TITLES=Map.of(
        "CASE_SUBMITTED","新案例或补充资料已提交 / Case submitted",
        "REVIEW_REQUEST_INFO","请补充售后资料 / More information required",
        "REVIEW_APPROVE","售后方案已审批 / Solution approved",
        "REVIEW_REJECT","售后申请未获批准 / Case declined",
        "REVIEW_REVISE","售后方案重新审核 / Solution under review",
        "SHIPMENT_DISPATCHED","配件已发货 / Replacement parts dispatched",
        "CASE_RESOLVED","售后问题已解决 / Case resolved",
        "CASE_CLOSED","售后案例已归档 / Case closed",
        "MESSAGE_ADDED","售后案例有新回复 / New case reply",
        "ATTACHMENTS_ADDED","售后案例有新附件 / New case attachments");

    static void enqueue(AfterSalesMapper mapper,long caseId,String action,Object snapshot) {
        String title=TITLES.get(action);if(title==null)return;
        var c=mapper.selectById(caseId);
        if(c==null)throw new BusinessException("AFTER_SALES_NOTIFICATION_FAILED","通知关联案例不存在");
        if(Set.of("MESSAGE_ADDED","ATTACHMENTS_ADDED").contains(action)&&"DRAFT".equals(c.getStatus()))return;
        Long eventId=mapper.selectInsertedId();
        if(eventId==null)throw new BusinessException("AFTER_SALES_NOTIFICATION_FAILED","通知缺少业务事件");
        String audience=action.equals("CASE_SUBMITTED") || (Set.of("MESSAGE_ADDED","ATTACHMENTS_ADDED").contains(action)&&AfterSalesAccess.isAgent())?"STAFF":"CUSTOMER";
        String recipient=audience.equals("CUSTOMER")?c.getContactEmail():null;
        String body="案例 / Case: "+c.getCaseNo()+"\n"+title+"\n";
        if(action.equals("SHIPMENT_DISPATCHED")) {
            var data=JSON.valueToTree(snapshot);if(data.has("shipment"))data=data.path("shipment");
            body+="承运商 / Carrier: "+data.path("carrier").asText("")+"\n运单号 / Tracking: "+data.path("trackingNo").asText("")+"\n";
        }
        body+="请登录售后系统查看完整资料并回复。\nPlease sign in to the service portal for details and replies.";
        mapper.insertNotification(caseId,eventId,action,audience,recipient,UUID.randomUUID().toString(),"["+c.getCaseNo()+"] "+title,body);
    }
}
