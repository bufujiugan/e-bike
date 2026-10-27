package com.bike.server.production;

import com.bike.server.common.BusinessException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EanBarcodeService {
  public void validate(String ean) {
    if (ean == null) return;
    if (!ean.matches("[0-9]{13}"))
      throw new BusinessException("BARCODE_INVALID", "EAN须为13位数字");
    int sum = 0;
    for (int index = 11, weight = 3; index >= 0; index--, weight = 4 - weight)
      sum += (ean.charAt(index) - '0') * weight;
    if ((10 - sum % 10) % 10 != ean.charAt(12) - '0')
      throw new BusinessException("BARCODE_INVALID", "EAN校验位不正确");
  }

  public String dataUrl(String ean) {
    validate(ean);
    try (var output = new ByteArrayOutputStream()) {
      MatrixToImageWriter.writeToStream(
          new MultiFormatWriter()
              .encode(
                  ean,
                  BarcodeFormat.EAN_13,
                  420,
                  96,
                  Map.of(EncodeHintType.MARGIN, 16)),
          "PNG",
          output);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (Exception error) {
      throw new IllegalStateException("EAN一维码生成失败", error);
    }
  }
}
