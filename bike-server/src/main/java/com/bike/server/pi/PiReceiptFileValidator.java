package com.bike.server.pi;

import com.bike.server.common.BusinessUploadValidator;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PiReceiptFileValidator {
    private final BusinessUploadValidator files;
    public PiReceiptFileValidator(BusinessUploadValidator files) { this.files = files; }
    public BusinessUploadValidator.PreparedUpload prepare(MultipartFile file) throws IOException { return files.prepare(file); }
}
