package com.bike.server.transfer;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 专用验收 API 内容检查；创建独立前缀数据，不连接正式环境。 */
@EnabledIfEnvironmentVariable(named="BIKE_ACCEPTANCE_WRITE", matches="isolated")
class FileApiAcceptanceTest {
  final HttpClient client=HttpClient.newHttpClient();
  final ObjectMapper json=new ObjectMapper();
  final String base=Optional.ofNullable(System.getenv("BIKE_API_URL")).orElse("http://127.0.0.1:18180");
  final String prefix="FILE"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
  String token;
  @BeforeEach void login() throws Exception {
    token=call("POST","/api/auth/login",Map.of("username","admin","password",System.getenv("BIKE_TEST_PASSWORD"))).path("data").path("accessToken").asText();
    assertThat(token).isNotBlank();
  }
  HttpResponse<byte[]> send(String method,String path,byte[] body,String contentType) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(30));
    if(token!=null)builder.header("Authorization","Bearer "+token);
    if(contentType!=null)builder.header("Content-Type",contentType);
    return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
  }
  JsonNode call(String method,String path,Object body) throws Exception {
    var response=send(method,path,body==null?null:json.writeValueAsBytes(body),"application/json");
    var result=json.readTree(response.body());
    assertThat(response.statusCode()).withFailMessage("%s: %s",path,result).isEqualTo(200);
    assertThat(result.path("code").asText()).isEqualTo("SUCCESS");return result;
  }
  HttpResponse<byte[]> upload(String path,String filename,byte[] file,String type) throws Exception {
    String boundary="BikeTest"+UUID.randomUUID();
    var bytes=new ByteArrayOutputStream();
    bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.write(file);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return send("POST",path,bytes.toByteArray(),"multipart/form-data; boundary="+boundary);
  }
  byte[] workbook(List<List<String>> rows) throws Exception {
    try(var workbook=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var sheet=workbook.createSheet("Import");sheet.createRow(0).createCell(0).setCellValue("header");
      for(int r=0;r<rows.size();r++){var row=sheet.createRow(r+1);for(int c=0;c<rows.get(r).size();c++)row.createCell(c).setCellValue(rows.get(r).get(c));}
      workbook.write(bytes);return bytes.toByteArray();
    }
  }
  @Test void realImportAndFilteredExportPreserveAllRowsAndReportFailures() throws Exception {
    var rows=new ArrayList<List<String>>();
    for(int i=0;i<105;i++)rows.add(List.of(prefix+String.format("%04d",i),"MOTOR","",prefix,"PASSED","验收"));
    rows.add(rows.getFirst());rows.add(List.of(prefix+"BAD","MOTOR","",prefix,"INVALID"));rows.add(List.of("","MOTOR"));
    var response=upload("/api/parts/import","parts.xlsx",workbook(rows),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.statusCode()).isEqualTo(200);
    var result=json.readTree(response.body()).path("data");
    assertThat(result.path("successRows").asInt()).isEqualTo(105);
    assertThat(result.path("failureRows").asInt()).isEqualTo(3);
    assertThat(result.path("failures").get(0).path("rowNumber").asInt()).isEqualTo(107);
    assertThat(result.path("failures").get(1).path("reason").asText()).contains("质检状态");
    assertThat(result.path("failures").get(2).path("rowNumber").asInt()).isEqualTo(109);
    var exported=send("GET","/api/export/parts?keyword="+prefix,null,null);
    assertThat(exported.statusCode()).isEqualTo(200);
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(exported.body()))) {
      var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(105);
      var values=new HashSet<String>();for(int r=1;r<=105;r++)values.add(sheet.getRow(r).getCell(0).getStringCellValue());
      assertThat(values).hasSize(105);for(int i=0;i<105;i++)assertThat(values).contains(prefix+String.format("%04d",i));
    }
    var empty=send("GET","/api/export/parts?keyword="+prefix+"NONE",null,null);
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(empty.body()))) {assertThat(book.getSheetAt(0).getLastRowNum()).isZero();}
    var template=send("GET","/api/parts/import-template",null,null);
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(template.body()))) {
      assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).contains("零部件SN");
      assertThat(book.getSheetAt(0).getColumnStyle(0).getDataFormatString()).isEqualTo("@");
    }
    var model=call("POST","/api/master/models",Map.of("code",prefix,"name",prefix)).path("data").path("id").asLong();
    var customer=call("POST","/api/master/customers",Map.of("code",prefix,"name",prefix)).path("data").path("id").asLong();
    var invalid=upload("/api/orders/import","orders.xlsx",workbook(List.of(List.of(""+customer,"",""+model,"0"))),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    var invalidResult=json.readTree(invalid.body()).path("data");
    assertThat(invalidResult.path("successRows").asInt()).isZero();
    assertThat(invalidResult.path("failures").get(0).path("reason").asText()).contains("数量必须大于");
  }
  @Test void skuImageRoundTripRejectsFakeImageAndUnauthenticatedRead() throws Exception {
    long model=call("POST","/api/master/models",Map.of("code",prefix,"name",prefix)).path("data").path("id").asLong();
    long template=call("GET","/api/production/templates",null).path("data").get(0).path("id").asLong();
    call("POST","/api/production/skus",Map.of("modelId",model,"skuCode",prefix,"productName",prefix,"templateId",template,"enabled",true));
    long sku=0;for(var row:call("GET","/api/production/skus",null).path("data"))if(prefix.equals(row.path("skuCode").asText()))sku=row.path("id").asLong();
    assertThat(sku).isPositive();
    var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,24,BufferedImage.TYPE_INT_RGB),"png",bytes);
    var uploaded=upload("/api/production/skus/"+sku+"/images","test.png",bytes.toByteArray(),"image/png");
    assertThat(uploaded.statusCode()).isEqualTo(200);
    String id=json.readTree(uploaded.body()).path("data").path("id").asText();assertThat(id).isNotBlank();
    var listed=call("GET","/api/production/skus/"+sku+"/images",null).path("data");assertThat(listed.size()).isEqualTo(1);assertThat(listed.get(0).path("id").asText()).isEqualTo(id);
    var downloaded=send("GET","/api/production/images/"+id,null,null);assertThat(downloaded.statusCode()).isEqualTo(200);assertThat(downloaded.body()).isEqualTo(bytes.toByteArray());
    var invalid=upload("/api/production/skus/"+sku+"/images","fake.png","not-an-image".getBytes(),"image/png");assertThat(invalid.statusCode()).isEqualTo(400);
    var truncated=upload("/api/production/skus/"+sku+"/images","truncated.png",Arrays.copyOf(bytes.toByteArray(),33),"image/png");
    assertThat(truncated.statusCode()).withFailMessage("仅有PNG头而无像素数据的文件必须拒绝").isEqualTo(400);
    assertThat(call("GET","/api/production/skus/"+sku+"/images",null).path("data").size()).isEqualTo(1);
    token=null;assertThat(send("GET","/api/production/images/"+id,null,null).statusCode()).isEqualTo(401);
  }
  @Test void inventoryExportPreservesCommitmentBreakdownAndShortages() throws Exception {
    var module=call("GET","/api/modules/inventory?pageSize=100",null).path("data");
    assertThat(module.path("total").asInt()).isPositive();
    String keyword=module.path("rows").get(0).get(0).asText();
    String query="?keyword="+java.net.URLEncoder.encode(keyword,java.nio.charset.StandardCharsets.UTF_8);
    var expected=new ArrayList<List<String>>();
    List<String> columns=new ArrayList<>();module.path("columns").forEach(c->columns.add(c.asText()));
    for(int page=1;;page++) {
      var data=call("GET","/api/modules/inventory"+query+"&pageSize=100&page="+page,null).path("data");
      for(var row:data.path("rows")){var values=new ArrayList<String>();row.forEach(c->values.add(c.asText()));expected.add(values);}
      if(expected.size()>=data.path("total").asInt())break;
    }
    assertThat(columns).contains("临时锁定","订单占用","库存缺口");
    var response=send("GET","/api/export/inventory"+query,null,null);
    assertThat(response.statusCode()).isEqualTo(200);
    try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(response.body()))) {
      var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(expected.size());
      var format=new DataFormatter();
      for(int c=0;c<columns.size();c++)assertThat(format.formatCellValue(sheet.getRow(0).getCell(c))).isEqualTo(columns.get(c));
      for(int r=0;r<expected.size();r++)for(int c=0;c<columns.size();c++)
        assertThat(format.formatCellValue(sheet.getRow(r+1).getCell(c))).isEqualTo(expected.get(r).get(c));
    }
  }
}
