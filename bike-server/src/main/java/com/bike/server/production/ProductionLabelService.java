package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class ProductionLabelService {
  private final ProductionService service;
  private final ObjectMapper json;

  public ProductionLabelService(ProductionService service, ObjectMapper json) {
    this.service = service;
    this.json = json;
  }

  public String render(String id) {
    var job = service.getPrint(id);
    try {
      var s = json.readValue(String.valueOf(job.get("snapshot")), PrintSnapshot.class);
      var output = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(
          new MultiFormatWriter()
              .encode(
                  s.qrCode(), BarcodeFormat.QR_CODE, 360, 360, Map.of(EncodeHintType.MARGIN, 2)),
          "PNG",
          output);
      String qr = Base64.getEncoder().encodeToString(output.toByteArray());
      String barcode =
          barcode(s.ean(), BarcodeFormat.EAN_13) + barcode(s.upc(), BarcodeFormat.UPC_A);
      String rows =
          row("Model", s.modelName())
              + row("Name", s.productName())
              + row("SKU", s.skuCode())
              + row("Spec", s.specification())
              + row("EAN", s.ean())
              + row("UPC", s.upc())
              + row("Batch", s.batchNo());
      return "<!doctype html><html lang='zh-CN'><head><meta"
                 + " charset='utf-8'><title>整车外箱标签</title><style>@page{size:"
          + s.widthMm()
          + "mm "
          + s.heightMm()
          + "mm;margin:0}*{box-sizing:border-box}body{margin:0;font-family:Arial,'Microsoft"
          + " YaHei',sans-serif;background:#eee}.label{background:white;width:"
          + s.widthMm()
          + "mm;min-height:"
          + s.heightMm()
          + "mm;padding:5mm;overflow-wrap:anywhere}h1{font-size:18pt;margin:0 0"
          + " 3mm}dl{margin:0;font-size:10pt}dt{font-weight:bold;float:left;clear:left;width:16mm}dd{margin:0"
          + " 0 1mm"
          + " 18mm}.identity{display:flex;align-items:center;gap:2mm;margin-top:3mm}.qr{width:"
          + ("COMPACT".equals(s.layout()) ? 30 : 38)
          + "mm;height:auto}.codes{font-size:8pt;line-height:1.6}.foot{font-size:7pt;border-top:1px"
          + " solid #222;margin-top:2mm;padding-top:2mm}@media"
          + " print{body{background:white}.label{break-after:page}}</style></head><body><article"
          + " class='label'><h1>"
          + escape(s.heading())
          + "</h1><dl>"
          + rows
          + "</dl>"
          + barcode
          + "<div class='identity'><img class='qr' src='data:image/png;base64,"
          + qr
          + "' alt='整车聚合码'><div class='codes'>Frame: "
          + escape(s.frameSn())
          + "<br>Motor: "
          + escape(s.motorSn())
          + "<br>Battery: "
          + escape(s.batterySn())
          + "<br>Display: "
          + escape(s.displaySn())
          + "</div></div><div class='foot'>"
          + escape(s.templateName())
          + " · V"
          + s.templateVersion()
          + " · "
          + escape(id)
          + "</div></article></body></html>";
    } catch (Exception e) {
      throw new IllegalStateException("生成生产标签失败，任务=" + id, e);
    }
  }

  private static String barcode(String value, BarcodeFormat format) throws Exception {
    if (value == null || value.isBlank()) return "";
    var output = new ByteArrayOutputStream();
    MatrixToImageWriter.writeToStream(
        new MultiFormatWriter().encode(value, format, 400, 70, Map.of(EncodeHintType.MARGIN, 20)),
        "PNG",
        output);
    return "<div style='display:inline-block;width:48%;margin-top:2mm;text-align:center'><img"
               + " style='width:100%;height:12mm' alt='"
        + format
        + "' src='data:image/png;base64,"
        + Base64.getEncoder().encodeToString(output.toByteArray())
        + "'><div style='font-size:8pt'>"
        + escape(value)
        + "</div></div>";
  }

  private static String row(String key, String value) {
    return value == null || value.isBlank()
        ? ""
        : "<dt>" + escape(key) + "</dt><dd>" + escape(value) + "</dd>";
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value);
  }
}
