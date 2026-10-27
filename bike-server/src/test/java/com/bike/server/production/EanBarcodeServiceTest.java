package com.bike.server.production;

import static org.assertj.core.api.Assertions.*;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.Test;

class EanBarcodeServiceTest {
  private final EanBarcodeService service = new EanBarcodeService();

  @Test
  void generatesPngDataUrlForValidEan13() {
    assertThat(service.dataUrl("6979176420209")).startsWith("data:image/png;base64,");
  }

  @Test
  void rejectsInvalidCheckDigit() {
    assertThatThrownBy(() -> service.dataUrl("6979176420208"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("校验位");
  }
}
