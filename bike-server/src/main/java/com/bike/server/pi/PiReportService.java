package com.bike.server.pi;

import com.bike.server.common.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PiReportService {
    private static final List<String> QUEUES=List.of("EXPIRING_LOCKS","MISSING_PI","AWAITING_PAYMENT","AWAITING_SHIPMENT","PARTIAL_SHIPMENT");
    private final PiReportMapper mapper;
    private final PiService pis;
    private final OperationLogMapper audit;
    private final CustomerScopeAccess scope;
    public PiReportService(PiReportMapper mapper,PiService pis,OperationLogMapper audit,CustomerScopeAccess scope) {
        this.mapper=mapper;this.pis=pis;this.audit=audit;this.scope=scope;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Long> summary(String keyword) {
        read();String query=keyword(keyword);var counts=new LinkedHashMap<String,Long>();
        for(String queue:QUEUES)counts.put(queue,allowed(queue)?mapper.count(queue,query):null);
        return counts;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Map<String,Object>> queue(String queue,String keyword,long page,long pageSize) {
        read();
        if(!QUEUES.contains(queue))throw new BusinessException("INVALID_PARAMETER","业务队列不存在");
        if(!allowed(queue))throw new BusinessException("PERMISSION_DENIED","当前账号无权查看此业务队列");
        long p=Math.min(10_000_000,Math.max(1,page)),size=Math.min(100,Math.max(1,pageSize));String query=keyword(keyword);
        var rows=mapper.rows(queue,query,(p-1)*size,size);
        for(var row:rows) {
            if(!FinancialAuditAccess.allowed())row.remove("paidAmount");
            // Preserve currency decimals across JSON clients, as the PI detail endpoint does.
            for(String field:List.of("totalAmount","paidAmount"))if(row.get(field) instanceof BigDecimal value)row.put(field,value.toPlainString());
        }
        return new PageResponse<>(rows,p,size,mapper.count(queue,query));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] export(String keyword,String status) {
        CurrentOperator.requireWebAccess();CurrentOperator.requirePermission("PI_READ");CurrentOperator.requirePermission("PI_EXPORT");
        scope.lockForBusiness();String query=keyword(keyword);
        var page=pis.list(query,status,1,100);long expected=page.total();
        if(expected>1_048_575)throw new BusinessException("EXPORT_LIMIT","PI列表超过Excel容量，请缩小查询范围");
        boolean finance=FinancialAuditAccess.allowed();
        try(var workbook=new SXSSFWorkbook(100);var output=new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);var sheet=workbook.createSheet("PI列表");
            var columns=new ArrayList<>(List.of("PI编号","版本","客户编号","客户","内外销","币种","总金额","状态","负责人","创建时间","计划发货","已发数量","未发数量","已关闭数量"));
            if(finance)columns.addAll(List.of("付款状态","已确认到账"));
            var heading=sheet.createRow(0);var bold=workbook.createFont();bold.setBold(true);var headingStyle=workbook.createCellStyle();headingStyle.setFont(bold);
            var amount=workbook.createCellStyle();amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            for(int c=0;c<columns.size();c++){var cell=heading.createCell(c);cell.setCellValue(columns.get(c));cell.setCellStyle(headingStyle);sheet.setColumnWidth(c,(c==0||c==3||c==9?26:17)*256);}
            int written=0;long pageNumber=1;
            while(true) {
                for(var pi:page.records()) {
                    var row=sheet.createRow(++written);int col=0;
                    text(row,col++,pi.getPiNo());number(row,col++,pi.getRevision());text(row,col++,pi.getCustomerCode());text(row,col++,pi.getCustomerName());
                    text(row,col++,"EXPORT".equals(pi.getTradeType())?"外销":"内销");text(row,col++,pi.getCurrencyCode());money(row,col++,pi.getTotalAmount(),amount);
                    text(row,col++,pi.getStatus());text(row,col++,pi.getBusinessOwner());text(row,col++,pi.getCreatedAt());text(row,col++,pi.getPlannedShipDate());
                    long shipped=pi.getFulfillment().stream().mapToLong(PiFulfillmentLine::shippedQty).sum();
                    number(row,col++,shipped);
                    if(pi.getFulfillment().isEmpty()){row.createCell(col++);row.createCell(col++);}
                    else {number(row,col++,pi.getFulfillment().stream().mapToLong(PiFulfillmentLine::remainingQty).sum());number(row,col++,pi.getFulfillment().stream().mapToLong(PiFulfillmentLine::closedQty).sum());}
                    if(finance){text(row,col++,pi.getPaymentStatus());money(row,col,pi.getPaidAmount(),amount);}
                }
                if(written>=expected)break;
                page=pis.list(query,status,++pageNumber,100);
                if(page.records().isEmpty())throw new BusinessException("EXPORT_INCOMPLETE","PI导出不完整，请重新查询");
            }
            if(written!=expected)throw new BusinessException("EXPORT_INCOMPLETE","PI导出记录数不一致");
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,written,0,columns.size()-1));
            workbook.write(output);
            if(audit.insert("PI_LIST_EXPORT","PI","导出PI列表",CurrentOperator.id(),CurrentOperator.username(),null,
                    "{\"rows\":"+written+"}",CurrentOperator.clientIp(),null)!=1)throw new BusinessException("EXPORT_AUDIT_FAILED","PI导出审计保存失败");
            return output.toByteArray();
        }catch(IOException error){throw new BusinessException("EXPORT_FAILED","PI列表导出失败");}
    }
    private static void read(){CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_READ");}
    private static boolean allowed(String queue) {
        if("AWAITING_PAYMENT".equals(queue))return FinancialAuditAccess.allowed();
        if("EXPIRING_LOCKS".equals(queue))return CurrentOperator.hasRole("SUPER_ADMIN")||CurrentOperator.hasPermission("AGENT_LOCK_WRITE")||CurrentOperator.hasPermission("AGENT_INVENTORY_READ");
        return true;
    }
    private static String keyword(String value){if(value==null||value.isBlank())return null;if(value.length()>200)throw new BusinessException("INVALID_PARAMETER","搜索内容不能超过200字");return value.trim();}
    private static void text(Row row,int column,Object value){if(value!=null)row.createCell(column).setCellValue(value.toString());else row.createCell(column);}
    private static void number(Row row,int column,long value){row.createCell(column).setCellValue(value);}
    private static void money(Row row,int column,BigDecimal value,CellStyle style) {
        var cell=row.createCell(column);cell.setCellStyle(style);
        if(value==null)return;
        if(value.setScale(2).precision()<=15
                && BigDecimal.valueOf(value.doubleValue()).setScale(2,java.math.RoundingMode.HALF_UP).compareTo(value)==0) {
            cell.setCellValue(value.doubleValue());return;
        }
        cell.setCellValue(value.setScale(2).toPlainString());
        var helper=row.getSheet().getWorkbook().getCreationHelper();var anchor=helper.createClientAnchor();
        anchor.setCol1(column);anchor.setCol2(column+3);anchor.setRow1(row.getRowNum());anchor.setRow2(row.getRowNum()+3);
        var comment=row.getSheet().createDrawingPatriarch().createCellComment(anchor);
        comment.setString(helper.createRichTextString("金额超过Excel安全数值精度，以文本保留原始分位；计算前请核对精度。"));cell.setCellComment(comment);
    }
}
