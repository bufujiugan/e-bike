package com.bike.server.pi;

import com.bike.server.aftersales.AfterSalesUploadValidator;
import com.bike.server.common.BusinessException;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PiReceiptFileValidator {
    public static final long LIMIT = 10L * 1024 * 1024;
    private final AfterSalesUploadValidator files;
    public PiReceiptFileValidator(AfterSalesUploadValidator files) { this.files = files; }
    public AfterSalesUploadValidator.PreparedUpload prepare(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > LIMIT)
            throw new BusinessException("ATTACHMENT_SIZE_INVALID", "凭证不能为空且单文件不能超过10MB");
        String name = file.getOriginalFilename();
        String extension = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png", "pdf").contains(extension))
            throw new BusinessException("ATTACHMENT_TYPE_UNSUPPORTED", "收款凭证仅支持 JPG、PNG、PDF");
        // The allowlist above excludes the shared validator's video and diagnostic paths.
        return files.prepare(file);
    }
}
