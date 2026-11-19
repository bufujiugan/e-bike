package com.bike.server.pi;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 从同一欧洲 Excel 版式输出 PDF，分页时保留表头与全部快照文字。 */
final class PiEuropeSheetPdfRenderer {
    private final PDFont latin=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    void fitRows(XSSFWorkbook book)throws IOException {
        try(var document=new PDDocument();var source=getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if(source==null)throw new IOException("Bundled PI font missing");
            var font=PDType0Font.load(document,source,true);var sheet=book.getSheetAt(0);var formatter=new DataFormatter(java.util.Locale.UK);
            for(var row:sheet)for(var cell:row) {
                String value=formatter.formatCellValue(cell);if(value.isEmpty())continue;
                var range=new CellRangeAddress(row.getRowNum(),row.getRowNum(),cell.getColumnIndex(),cell.getColumnIndex());
                for(var merged:sheet.getMergedRegions())if(merged.isInRange(row.getRowNum(),cell.getColumnIndex())){range=merged;break;}
                if(range.getFirstRow()!=row.getRowNum()||range.getFirstColumn()!=cell.getColumnIndex())continue;
                float width=0;for(int col=range.getFirstColumn();col<=range.getLastColumn();col++)width+=sheet.getColumnWidthInPixels(col)*.75f;
                float size=book.getFontAt(cell.getCellStyle().getFontIndex()).getFontHeightInPoints();
                PDFont selected=value.codePoints().allMatch(cp->cp<128)?latin:font;
                float required=wrap(selected,value,size,width-5).size()*(size+2)+4;
                float current=height(sheet,range.getFirstRow(),range.getLastRow());
                if(required>current)row.setHeightInPoints(row.getHeightInPoints()+required-current);
            }
        }
    }
    byte[] render(XSSFWorkbook book,PiView pi) throws IOException {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream();
            var fontSource=getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if(fontSource==null)throw new IOException("Bundled PI font missing");
            var font=PDType0Font.load(document,fontSource,true);
            document.getDocumentInformation().setTitle("PI "+pi.getPiNo()+" R"+pi.getRevision());
            var sheet=book.getSheetAt(0);float[] x=new float[13];x[0]=24;
            float total=0;for(int col=0;col<12;col++)total+=sheet.getColumnWidthInPixels(col)*.75f;
            float scale=Math.min(1,547/total);x[0]=(595-total*scale)/2;
            for(int col=0;col<12;col++)x[col+1]=x[col]+sheet.getColumnWidthInPixels(col)*.75f*scale;
            PDPageContentStream stream=null;float y=0;
            try {
                for(int row=0;row<=sheet.getLastRowNum();) {
                    int end=row;
                    for(var merged:sheet.getMergedRegions())if(merged.getFirstRow()<=row&&merged.getLastRow()>=row)end=Math.max(end,merged.getLastRow());
                    float height=height(sheet,row,end)*scale;
                    if(stream==null||y-height<45&&height<745) {
                        if(stream!=null)stream.close();
                        var page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=812;
                        if(row==0&&!book.getAllPictures().isEmpty()) {
                            var image=PDImageXObject.createFromByteArray(document,book.getAllPictures().getFirst().getData(),"brand");
                            float width=x[6]-x[0];stream.drawImage(image,x[0],y-65,width,Math.min(65,width*image.getHeight()/image.getWidth()));
                        }
                        if(row>=32) {drawRows(stream,font,sheet,31,31,x,y,scale);y-=height(sheet,31,31)*scale;}
                    }
                    // 极长地址跨页显示；裁切仅限制当前页，余下文字在下一页继续绘制。
                    float consumed=0;
                    do {
                        float available=y-45,portion=Math.min(height-consumed,available);
                        stream.saveGraphicsState();stream.addRect(23,45,549,available);stream.clip();
                        drawRows(stream,font,sheet,row,end,x,y+consumed,scale);stream.restoreGraphicsState();
                        consumed+=portion;y-=portion;
                        if(consumed<height-.01f) {
                            stream.close();var page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=812;
                        }
                    } while(consumed<height-.01f);
                    row=end+1;
                }
            } finally {if(stream!=null)stream.close();}
            for(int page=0;page<document.getNumberOfPages();page++) {
                try(var footer=new PDPageContentStream(document,document.getPage(page),PDPageContentStream.AppendMode.APPEND,true,true)) {
                    drawText(footer,font,pi.getPiNo()+" / R"+pi.getRevision(),8,24,22,false);
                    drawText(footer,font,"Page "+(page+1)+" / "+document.getNumberOfPages(),8,490,22,false);
                }
            }
            document.save(output);return output.toByteArray();
        }
    }

    private void drawRows(PDPageContentStream stream,PDType0Font font,Sheet sheet,int start,int end,float[] x,float y,float scale) throws IOException {
        var formatter=new DataFormatter(java.util.Locale.UK);
        for(int rowIndex=start;rowIndex<=end;rowIndex++) {
            var row=sheet.getRow(rowIndex);float rowHeight=height(sheet,rowIndex,rowIndex)*scale;
            if(row!=null)for(int col=0;col<12;col++) {
                var cell=row.getCell(col);if(cell==null)continue;
                CellRangeAddress range=null;boolean skip=false;
                for(var merged:sheet.getMergedRegions())if(merged.isInRange(rowIndex,col)) {
                    range=merged;skip=merged.getFirstRow()!=rowIndex||merged.getFirstColumn()!=col;break;
                }
                if(skip)continue;
                int last=range==null?col:range.getLastColumn();float width=x[last+1]-x[col]-5;
                String value=formatter.formatCellValue(cell);if(value.isEmpty())continue;
                var sourceFont=sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndex());
                float size=Math.max(8,sourceFont.getFontHeightInPoints()*scale);
                PDFont textFont=value.codePoints().allMatch(cp->cp<128)?latin:font;
                var lines=wrap(textFont,value,size,width);
                float baseline=y-size-2;
                for(String line:lines) {
                    float left=x[col]+2;
                    var align=cell.getCellStyle().getAlignment();
                    if(align==HorizontalAlignment.RIGHT)left=x[last+1]-3-textFont.getStringWidth(line)/1000*size;
                    if(align==HorizontalAlignment.CENTER)left=x[col]+(width-textFont.getStringWidth(line)/1000*size)/2;
                    drawText(stream,textFont,line,size,left,baseline,sourceFont.getBold());baseline-=size+2;
                }
            }
            y-=rowHeight;
        }
    }
    private static float height(Sheet sheet,int first,int last){float value=0;for(int row=first;row<=last;row++)value+=sheet.getRow(row)==null?sheet.getDefaultRowHeightInPoints():sheet.getRow(row).getHeightInPoints();return value;}
    private static List<String> wrap(PDFont font,String value,float size,float width)throws IOException {
        var lines=new ArrayList<String>();
        for(String paragraph:value.replace('\t',' ').replace("\r","").split("\n",-1)) {
            var line=new StringBuilder();
            for(int cp:paragraph.codePoints().toArray()) {
                var next=new String(Character.toChars(cp));
                if(!line.isEmpty()&&font.getStringWidth(line+next)/1000*size>width){lines.add(line.toString());line.setLength(0);}
                line.append(next);
            }
            lines.add(line.toString());
        }
        return lines;
    }
    private static void drawText(PDPageContentStream stream,PDFont font,String text,float size,float x,float y,boolean bold)throws IOException {
        stream.beginText();stream.setFont(font,size);stream.setRenderingMode(bold?RenderingMode.FILL_STROKE:RenderingMode.FILL);
        stream.setLineWidth(.18f);stream.newLineAtOffset(x,y);stream.showText(text);stream.endText();
    }
}
