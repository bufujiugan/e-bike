package com.bike.server.common;

import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Existing business tests isolate customer scope; dedicated scope tests use the real mapper and service. */
public final class CustomerScopeTestSupport {
    private CustomerScopeTestSupport() {}
    public static <T> T isolated(T service) {
        ReflectionTestUtils.setField(service,"customerScope",mock(CustomerScopeAccess.class));
        if(service instanceof com.bike.server.pi.PiReceiptAttachmentService) {
            var pis=mock(com.bike.server.pi.PiMapper.class);
            when(pis.selectById(anyLong())).thenReturn(new com.bike.server.pi.PiView());
            ReflectionTestUtils.setField(service,"pis",pis);
        }
        return service;
    }
}
