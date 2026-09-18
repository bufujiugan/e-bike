package com.bike.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionLabelServiceTest {
  @Test void labelContainsDecodableQrEanUpcAndEscapesProductText()throws Exception{
    var service=mock(ProductionService.class);var json=new ObjectMapper();
    var snapshot=new ProductionContracts.PrintSnapshot("F-001","M-001","B-001","D-001","BIKE:10:token","SKU-1","车型","<script>alert(1)</script>","48V","4006381333931","036000291452","B1","标准",1,100,150,"STANDARD","E-BIKE");
    when(service.getPrint("job")).thenReturn(Map.of("snapshot",json.writeValueAsString(snapshot)));
    String html=new ProductionLabelService(service,json).render("job");
    assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    var matcher=Pattern.compile("base64,([A-Za-z0-9+/=]+)").matcher(html);var values=new ArrayList<String>();
    while(matcher.find()){
      var image=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(matcher.group(1))));
      var bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
      values.add(new MultiFormatReader().decode(bitmap).getText());
    }
    assertThat(values).contains("BIKE:10:token","4006381333931");
    assertThat(values).anyMatch(v->v.equals("036000291452")||v.equals("0036000291452"));
  }
}
