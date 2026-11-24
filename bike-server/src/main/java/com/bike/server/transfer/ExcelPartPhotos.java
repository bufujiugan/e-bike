package com.bike.server.transfer;

import com.bike.server.common.BusinessException;
import com.bike.server.common.BusinessUploadValidator;
import com.bike.server.part.PartPhotoUpload;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ExcelPartPhotos {
    private static final String WPS_IMAGE_RELATION = "http://www.wps.cn/officeDocument/2020/cellImage";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String SPREADSHEET_DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String RELATION_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern WPS_IMAGE_FORMULA = Pattern.compile(
            "(?i)^\\s*=?(?:_xlfn\\.)?DISPIMG\\s*\\(\\s*\"([A-Za-z0-9_-]{1,128})\"\\s*[,;].*");

    private final int column;
    private final Map<Integer, List<Picture>> anchored = new HashMap<>();
    private final Map<String, PackagePart> wpsImages;

    ExcelPartPhotos(Sheet sheet, int column) throws IOException, InvalidFormatException, SAXException {
        this.column = column;
        wpsImages = column >= 0 && sheet.getWorkbook() instanceof XSSFWorkbook workbook
                ? readWpsImages(workbook) : Map.of();
        var drawing = column < 0 ? null : sheet.getDrawingPatriarch();
        if (drawing == null) return;
        for (var shape : drawing) {
            if (shape instanceof Picture picture && picture.getClientAnchor() != null
                    && picture.getClientAnchor().getCol1() == column) {
                anchored.computeIfAbsent(picture.getClientAnchor().getRow1(), row -> new ArrayList<>()).add(picture);
            }
        }
    }

    static int findColumn(Row header) {
        if (header == null) return -1;
        for (Cell cell : header) {
            if (cell.getCellType() != CellType.STRING) continue;
            String label = cell.getStringCellValue().trim();
            if (label.equalsIgnoreCase("Photo") || label.equals("图片") || label.equals("图片（可选）")) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    PartPhotoUpload photoAt(Row row) {
        if (column < 0) return null;
        Cell cell = row.getCell(column);
        List<Picture> pictures = anchored.getOrDefault(row.getRowNum(), List.of());
        boolean hasCellValue = cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank();
        if (pictures.size() + (hasCellValue ? 1 : 0) > 1) {
            throw new BusinessException("IMPORT_PART_PHOTO_MULTIPLE", "每个零部件只能导入一张图片");
        }
        try {
            if (hasCellValue) return readCellImage(cell);
            if (pictures.isEmpty()) return null;
            PictureData picture = pictures.getFirst().getPictureData();
            if (picture instanceof XSSFPictureData xssf) return readPackageImage(xssf.getPackagePart());
            if (picture != null) return PartPhotoUpload.from("photo." + picture.suggestFileExtension(), picture.getData());
        } catch (IOException error) {
            throw new BusinessException("IMPORT_PART_PHOTO_INVALID", "Photo 列图片无法读取");
        }
        throw invalidPhoto();
    }

    private PartPhotoUpload readCellImage(Cell cell) throws IOException {
        String value = cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : cell.toString();
        var match = WPS_IMAGE_FORMULA.matcher(value);
        if (!match.matches()) throw invalidPhoto();
        PackagePart image = wpsImages.get(match.group(1));
        if (image == null) throw invalidPhoto();
        return readPackageImage(image);
    }

    private static PartPhotoUpload readPackageImage(PackagePart image) throws IOException {
        String extension = image.getPartName().getExtension();
        try (var stream = image.getInputStream()) {
            byte[] bytes = stream.readNBytes((int) BusinessUploadValidator.IMAGE_LIMIT + 1);
            if (bytes.length > BusinessUploadValidator.IMAGE_LIMIT) {
                throw new BusinessException("IMPORT_PART_PHOTO_TOO_LARGE", "Photo 列图片不能超过 20 MB");
            }
            return PartPhotoUpload.from("photo." + extension, bytes);
        }
    }

    private static Map<String, PackagePart> readWpsImages(XSSFWorkbook workbook)
            throws IOException, InvalidFormatException, SAXException {
        var workbookPart = workbook.getPackagePart();
        var relationships = workbookPart.getRelationshipsByType(WPS_IMAGE_RELATION);
        Map<String, PackagePart> images = new HashMap<>();
        for (var relationship : relationships) {
            if (relationship.getTargetMode() != TargetMode.INTERNAL) continue;
            PackagePart index = workbookPart.getRelatedPart(relationship);
            try (var input = index.getInputStream()) {
                // WPS 通过 DISPIMG 中的 ID 引用单元格图片，图片关系只读取工作簿内部资源。
                var document = XMLHelper.newDocumentBuilder().parse(input);
                var pictures = document.getElementsByTagNameNS(SPREADSHEET_DRAWING_NS, "pic");
                for (int i = 0; i < pictures.getLength(); i++) {
                    var picture = (Element) pictures.item(i);
                    var names = picture.getElementsByTagNameNS(SPREADSHEET_DRAWING_NS, "cNvPr");
                    var blips = picture.getElementsByTagNameNS(DRAWING_NS, "blip");
                    if (names.getLength() != 1 || blips.getLength() != 1) continue;
                    String name = ((Element) names.item(0)).getAttribute("name");
                    String id = ((Element) blips.item(0)).getAttributeNS(RELATION_NS, "embed");
                    var media = index.getRelationship(id);
                    if (!name.isBlank() && media != null && media.getTargetMode() == TargetMode.INTERNAL
                            && (RELATION_NS + "/image").equals(media.getRelationshipType())) {
                        images.put(name, index.getRelatedPart(media));
                    }
                }
            }
        }
        return images;
    }

    private static BusinessException invalidPhoto() {
        return new BusinessException("IMPORT_PART_PHOTO_INVALID", "Photo 列图片无效，请插入 JPG、PNG 或 WebP 图片");
    }
}
