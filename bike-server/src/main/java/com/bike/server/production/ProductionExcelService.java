package com.bike.server.production;

import com.bike.server.common.BusinessException;
import com.bike.server.transfer.ImportResult;
import jakarta.validation.Validator;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 商品逐行导入，每行调用独立事务；错误行不影响已成功的商品。 */
@Service
public class ProductionExcelService {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ProductionExcelService.class);
  private static final List<String> HEADERS =
      List.of("车型ID", "SKU编码", "商品名称", "规格", "EAN", "UPC", "模板ID", "启用(true/false)");
  private final ProductionService service;
  private final Validator validator;

  public ProductionExcelService(ProductionService service, Validator validator) {
    this.service = service;
    this.validator = validator;
  }

  public byte[] template() {
    ProductionAccess.manage();
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("SKU");
      var row = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) {
        row.createCell(i).setCellValue(HEADERS.get(i));
        sheet.setColumnWidth(i, 6000);
      }
      var textStyle = workbook.createCellStyle();
      textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
      for (int i = 0; i < HEADERS.size(); i++) sheet.setDefaultColumnStyle(i, textStyle);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("SKU模板生成失败", error);
    }
  }

  public ImportResult importSkus(MultipartFile file) {
    ProductionAccess.manage();
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw new BusinessException("IMPORT_FILE_INVALID", "文件不能为空且不能超过10MB");
    List<ImportResult.ImportFailure> failures = new ArrayList<>();
    int total = 0, success = 0;
    try (var input = file.getInputStream();
        var workbook = WorkbookFactory.create(input)) {
      if (workbook.getNumberOfSheets() == 0)
        throw new BusinessException("IMPORT_FILE_INVALID", "工作表不存在");
      var sheet = workbook.getSheetAt(0);
      if (sheet.getLastRowNum() > 5000) throw new BusinessException("IMPORT_LIMIT", "每次最多导入5000行");
      var formatter = new DataFormatter();
      for (int i = 1; i <= sheet.getLastRowNum(); i++) {
        var row = sheet.getRow(i);
        if (row == null) continue;
        String[] values = new String[8];
        for (int c = 0; c < 8; c++) values[c] = formatter.formatCellValue(row.getCell(c)).trim();
        if (Arrays.stream(values).allMatch(String::isBlank)) continue;
        total++;
        try {
          if (!Set.of("true", "false").contains(values[7]))
            throw new BusinessException("IMPORT_VALUE", "启用列须为true或false");
          var request =
              new ProductionContracts.SkuRequest(
                  Long.valueOf(values[0]),
                  values[1],
                  values[2],
                  values[3],
                  values[4],
                  values[5],
                  Long.valueOf(values[6]),
                  Boolean.parseBoolean(values[7]),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null);
          var errors = validator.validate(request);
          if (!errors.isEmpty()) throw new BusinessException("IMPORT_VALUE", "必填字段或长度不符合要求");
          service.saveSku(null, request);
          success++;
        } catch (BusinessException error) {
          failures.add(new ImportResult.ImportFailure(i + 1, error.getMessage()));
        } catch (NumberFormatException error) {
          failures.add(new ImportResult.ImportFailure(i + 1, "车型ID和模板ID须为整数"));
        } catch (RuntimeException error) {
          LOG.error("SKU导入失败 rowNumber={}", i + 1, error);
          failures.add(new ImportResult.ImportFailure(i + 1, "保存失败，请联系管理员"));
        }
      }
      return new ImportResult(total, success, failures.size(), failures);
    } catch (IOException error) {
      throw new BusinessException("IMPORT_FILE_INVALID", "无法读取Excel文件");
    } catch (org.apache.poi.EncryptedDocumentException | org.apache.poi.UnsupportedFileFormatException error) {
      throw new BusinessException("IMPORT_FILE_INVALID", "请使用未加密的有效Excel文件");
    }
  }
}
