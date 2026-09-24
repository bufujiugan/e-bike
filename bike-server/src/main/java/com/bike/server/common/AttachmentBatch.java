package com.bike.server.common;

import java.io.IOException;
import java.util.*;
import org.springframework.web.multipart.*;

/** Each file commits independently through the proxied service; a failed item can be retried by its original key. */
public final class AttachmentBatch {
    private AttachmentBatch() {}
    public static void requireSingle(MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1)
            throw new BusinessException("INVALID_PARAMETER", "单文件接口须且只能提供一个file文件");
    }
    public record Item<T>(String requestId, boolean success, T attachment, String code, String message) {}
    @FunctionalInterface public interface Upload<T> { T apply(String requestId, MultipartFile file) throws IOException; }
    public static <T> List<Item<T>> upload(List<String> requestIds, List<MultipartFile> files, MultipartHttpServletRequest request, Upload<T> upload) {
        if (files == null || files.isEmpty() || files.size() > 20 || requestIds == null || requestIds.size() != files.size()
                || request.getMultiFileMap().size() != 1 || request.getFiles("files").size() != files.size())
            throw new BusinessException("INVALID_PARAMETER", "每批须上传1至20个files文件并提供对应requestIds");
        var unique = new HashSet<String>();
        for (String id : requestIds) if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !unique.add(id))
            throw new BusinessException("INVALID_PARAMETER", "同批文件请求标识必须合法且不重复");
        var results = new ArrayList<Item<T>>();
        for (int i = 0; i < files.size(); i++) {
            String id = requestIds.get(i);
            try { results.add(new Item<>(id, true, upload.apply(id, files.get(i)), "SUCCESS", "文件已保存")); }
            catch (BusinessException error) { results.add(new Item<>(id, false, null, error.getCode(), error.getMessage())); }
            catch (IOException error) { results.add(new Item<>(id, false, null, "ATTACHMENT_IO_FAILED", "文件读取或保存失败，可单独重试")); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(AttachmentBatch.class).error("附件批次单项失败 requestId={}", id, error);
                results.add(new Item<>(id, false, null, "ATTACHMENT_SAVE_FAILED", "文件未完成保存，可使用原请求单独重试"));
            }
        }
        return results;
    }
}
