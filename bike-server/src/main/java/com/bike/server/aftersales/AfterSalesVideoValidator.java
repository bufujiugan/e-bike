package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.common.Codec;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded MP4 metadata validation followed by exactly one decoded H.264 frame. */
@Component
public class AfterSalesVideoValidator {
    private static final Logger LOG = LoggerFactory.getLogger(AfterSalesVideoValidator.class);
    private static final Semaphore WORKERS = new Semaphore(2);
    private static final int WORKER_TIMEOUT_SECONDS = 15;
    private final long maxPixels;
    private final int maxSeconds;
    private final int maxFrames;
    public AfterSalesVideoValidator(@Value("${bike.after-sales.video-max-pixels:8294400}") long maxPixels,
        @Value("${bike.after-sales.video-max-seconds:600}") int maxSeconds,
        @Value("${bike.after-sales.video-max-frames:72000}") int maxFrames) {
        if (maxPixels <= 0 || maxPixels > 16_777_216 || maxSeconds <= 0 || maxSeconds > 1800 || maxFrames <= 0 || maxFrames > 216000)
            throw new IllegalArgumentException("售后视频限额超出安全范围");
        this.maxPixels = maxPixels; this.maxSeconds = maxSeconds; this.maxFrames = maxFrames;
    }
    public void validate(Path path) throws IOException {
        // No third-party media parser runs in the request JVM. The manual scan is bounded by file/table sizes.
        preflight(path, new FrameBounds());
        if (!WORKERS.tryAcquire()) throw new BusinessException("ATTACHMENT_VALIDATION_BUSY", "视频校验繁忙，请稍后重试");
        Process worker = null;
        try {
            var process = new ProcessBuilder(workerCommand(path));
            process.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Child validation does not need database/storage credentials or externally supplied JVM options.
            var environment = process.environment(); var inherited = new HashMap<>(environment); environment.clear();
            for (String key : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL")) if (inherited.containsKey(key)) environment.put(key, inherited.get(key));
            worker = process.start();
            if (!worker.waitFor(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw invalid("视频校验超时，请缩短视频或重新编码");
            if (worker.exitValue() != 0) throw invalid("视频内容损坏、编码不支持或超出校验资源限制");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw invalid("视频校验已取消，请重试");
        } catch (IOException error) {
            LOG.error("售后视频独立校验进程无法启动", error);
            throw new BusinessException("ATTACHMENT_VALIDATION_UNAVAILABLE", "视频校验暂不可用，请稍后重试");
        } finally {
            if (worker != null && worker.isAlive()) {
                worker.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { worker.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
                // Keep its concurrency permit until the OS has actually reaped the cancelled process.
                if (worker.isAlive()) worker.onExit().whenComplete((ignored, error) -> WORKERS.release());
                else WORKERS.release();
            } else WORKERS.release();
        }
    }
    private List<String> workerCommand(Path path) throws IOException {
        Path runtime = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toRealPath();
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) throw new IOException("Missing application classpath");
        var command = new ArrayList<>(List.of(runtime.toString(), "-Xmx192m", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxDirectMemorySize=64m",
            "-XX:MaxMetaspaceSize=96m", "-XX:ReservedCodeCacheSize=32m", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8"));
        if (isApplicationJar(classpath)) command.addAll(List.of("-jar", Path.of(classpath).toAbsolutePath().toString(), AfterSalesVideoWorker.ARGUMENT));
        else command.addAll(List.of("-cp", classpath, AfterSalesVideoWorker.class.getName()));
        command.addAll(List.of(path.toAbsolutePath().normalize().toString(), Long.toString(maxPixels), Integer.toString(maxSeconds), Integer.toString(maxFrames)));
        return command;
    }
    private static boolean isApplicationJar(String classpath) throws IOException {
        if (!classpath.toLowerCase(Locale.ROOT).endsWith(".jar") || classpath.contains(File.pathSeparator) && !System.getProperty("os.name").startsWith("Windows")) return false;
        Path jar;
        try { jar = Path.of(classpath); } catch (InvalidPathException error) { return false; }
        if (!Files.isRegularFile(jar)) return false;
        try (var archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest();
            return manifest != null && "com.bike.server.BikeServerApplication".equals(manifest.getMainAttributes().getValue("Start-Class"));
        }
    }
    private void preflight(Path path, FrameBounds bounds) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) <= 0 || Files.size(path) > 50L * 1024 * 1024) throw invalid("视频文件大小不合法");
        try (var input = new RandomAccessFile(path.toFile(), "r")) {
            boolean[] found = new boolean[3]; inspectBoxes(input, 0, input.length(), 0, found, bounds);
            if (!found[0] || !found[1] || !found[2]) throw invalid("MP4必须包含视频头、媒体索引和实际媒体数据");
        }
    }
    /** Only the dedicated, memory/time-limited child process may call this method. */
    void validateInProcess(Path path) throws IOException {
        try {
            var bounds = new FrameBounds();
            preflight(path, bounds);
            try (var channel = NIOUtils.readableChannel(path.toFile()); var demuxer = MP4Demuxer.createMP4Demuxer(channel)) {
                var track = demuxer.getVideoTrack();
                if (track == null || demuxer.getVideoTracks().size() != 1) throw invalid("须包含单个可读取的视频轨道");
                var meta = track.getMeta();
                if (meta.getCodec() != Codec.H264 || meta.getVideoCodecMeta() == null) throw invalid("视频须为MP4容器中的H.264编码");
                var size = meta.getVideoCodecMeta().getSize();
                dimensions(size.getWidth(), size.getHeight());
                if (!Double.isFinite(meta.getTotalDuration()) || meta.getTotalDuration() <= 0 || meta.getTotalDuration() > maxSeconds
                    || meta.getTotalFrames() <= 0 || meta.getTotalFrames() > maxFrames) throw invalid("视频时长或帧数超出限制");
                var packet = track.nextFrame();
                if (packet == null || packet.getData() == null) throw invalid("视频没有可读取的首帧");
                ByteBuffer firstFrame = inspectFirstFrame(packet.getData(), bounds);
                dimensions(bounds.width, bounds.height);
                // Decode only this preflighted IDR packet; FrameGrab may decode leading packets implicitly.
                var buffer = Picture.create(bounds.width, bounds.height, ColorSpace.YUV420);
                var picture = new H264Decoder().decodeFrame(firstFrame, buffer.getData());
                if (picture == null) throw invalid("视频没有可解码画面");
                dimensions(picture.getWidth(), picture.getHeight());
            }
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw invalid("视频内容损坏或编码不支持，请上传MP4(H.264)"); }
    }

    // Check claimed table lengths before JCodec can allocate arrays from untrusted entry counts.
    private void inspectBoxes(RandomAccessFile input, long start, long end, int depth, boolean[] found, FrameBounds bounds) throws IOException {
        if (depth > 10) throw invalid("MP4媒体结构嵌套过深");
        long position = start; int boxes = 0;
        while (position < end) {
            if (++boxes > 10000 || end - position < 8) throw invalid("MP4媒体结构不完整");
            input.seek(position);
            long length = Integer.toUnsignedLong(input.readInt());
            byte[] code = new byte[4]; input.readFully(code); String type = new String(code, StandardCharsets.US_ASCII);
            int header = 8;
            if (length == 1) { if (end - position < 16) throw invalid("MP4长度不合法"); length = input.readLong(); header = 16; }
            else if (length == 0) length = end - position;
            if (length < header || length > end - position) throw invalid("MP4媒体块超出文件长度");
            long body = position + header, bodySize = length - header, next = position + length;
            if (type.equals("ftyp")) { if (bodySize < 8) throw invalid("MP4类型头不完整"); found[0] = true; }
            if (type.equals("mdat")) { if (bodySize == 0) throw invalid("MP4没有媒体数据"); found[2] = true; }
            if (type.equals("moov")) { if (bodySize > 8 * 1024 * 1024) throw invalid("MP4媒体索引过大"); found[1] = true; }
            if (Set.of("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf").contains(type)) inspectBoxes(input, body, next, depth + 1, found, bounds);
            else if (Set.of("stts", "ctts", "stsc", "stco", "co64", "stss", "elst").contains(type)) {
                if (bodySize < 8) throw invalid("MP4索引不完整");
                input.seek(body); int version = input.readInt() >>> 24; long count = Integer.toUnsignedLong(input.readInt());
                int stride = switch (type) { case "stsc" -> 12; case "stco", "stss" -> 4; case "elst" -> version == 1 ? 20 : 12; default -> 8; };
                if (count > Math.max(216000, maxFrames) || count > (bodySize - 8) / stride) throw invalid("MP4索引数量或长度不合法");
            } else if (type.equals("stsz")) {
                if (bodySize < 12) throw invalid("MP4样本表不完整");
                input.seek(body + 4); long sampleSize = Integer.toUnsignedLong(input.readInt()), count = Integer.toUnsignedLong(input.readInt());
                if (count > 216000 || sampleSize > 8 * 1024 * 1024 || (sampleSize == 0 && count > (bodySize - 12) / 4)) throw invalid("MP4样本表长度不合法");
                if (sampleSize == 0) for (long i = 0; i < count; i++) if (Integer.toUnsignedLong(input.readInt()) > 8 * 1024 * 1024) throw invalid("单个视频样本过大");
            } else if (type.equals("stsd")) {
                if (bodySize < 8) throw invalid("MP4编码描述不完整");
                input.seek(body + 4); long count = Integer.toUnsignedLong(input.readInt());
                if (count < 1 || count > 16) throw invalid("MP4编码描述数量不合法");
                inspectBoxes(input, body + 8, next, depth + 1, found, bounds);
            } else if (type.equals("avc1")) {
                if (bodySize < 78) throw invalid("H.264视频头不完整");
                input.seek(body + 24); dimensions(input.readUnsignedShort(), input.readUnsignedShort());
                inspectBoxes(input, body + 78, next, depth + 1, found, bounds);
            } else if (type.equals("avcC")) {
                if (bodySize < 7 || bodySize > 65536) throw invalid("H.264编码参数不合法");
                input.seek(body + 5); int spsCount = input.readUnsignedByte() & 31;
                if (spsCount < 1 || spsCount > 8) throw invalid("H.264序列参数不合法");
                for (int i = 0; i < spsCount; i++) {
                    if (next - input.getFilePointer() < 2) throw invalid("H.264序列参数截断");
                    int size = input.readUnsignedShort();
                    if (size < 4 || size > 4096 || size > next - input.getFilePointer()) throw invalid("H.264序列参数长度不合法");
                    byte[] sps = new byte[size]; input.readFully(sps); validateSps(sps, bounds);
                }
                if (input.getFilePointer() >= next) throw invalid("H.264图像参数缺失");
                int ppsCount = input.readUnsignedByte();
                if (ppsCount < 1 || ppsCount > 16) throw invalid("H.264图像参数数量超限");
                for (int i = 0; i < ppsCount; i++) {
                    if (next - input.getFilePointer() < 2) throw invalid("H.264图像参数截断");
                    int size = input.readUnsignedShort();
                    if (size < 2 || size > 4096 || size > next - input.getFilePointer()) throw invalid("H.264图像参数长度不合法");
                    byte[] pps = new byte[size]; input.readFully(pps); validatePps(pps);
                }
            } else if (type.equals("moof") || type.equals("stz2")) throw invalid("请上传普通MP4文件，暂不支持分段视频");
            position = next;
        }
    }
    private static Bits rbsp(byte[] nal, int expectedType) {
        if (nal.length < 2 || nal.length > 4096 || (nal[0] & 128) != 0 || (nal[0] & 31) != expectedType) throw invalid("H.264参数类型或长度不正确");
        byte[] raw = new byte[nal.length - 1]; int count = 0, zeros = 0;
        for (int i = 1; i < nal.length; i++) {
            int value = nal[i] & 255;
            if (zeros >= 2 && value == 3) { zeros = 0; continue; }
            raw[count++] = nal[i]; zeros = value == 0 ? zeros + 1 : 0;
        }
        return new Bits(raw, count);
    }
    // Fully inspect SPS/PPS before JCodec parses them; HRD and FMO also contain allocation sizes.
    private void validateSps(byte[] nal, FrameBounds bounds) {
        var bits = rbsp(nal, 7);
        int profile = bits.read(8); bits.read(16); bits.ue(31, "序列参数编号");
        if (!Set.of(66, 77, 88, 100, 110, 122, 144).contains(profile)) throw invalid("H.264编码profile暂不支持");
        if (Set.of(100, 110, 122, 144).contains(profile)) {
            int chroma = bits.ue(); if (chroma != 1 || bits.ue() != 0 || bits.ue() != 0) throw invalid("仅支持8位YUV420 H.264视频");
            bits.read(1);
            if (bits.read(1) == 1) for (int i = 0; i < 8; i++) if (bits.read(1) == 1) scalingList(bits, i < 6 ? 16 : 64);
        }
        if (bits.ue() > 12) throw invalid("H.264帧编号参数超限");
        int order = bits.ue();
        if (order == 0) { if (bits.ue() > 12) throw invalid("H.264排序参数超限"); }
        else if (order == 1) { bits.read(1); bits.se(); bits.se(); int cycle = bits.ue(); if (cycle > 256) throw invalid("H.264参考帧周期超限"); for (int i = 0; i < cycle; i++) bits.se(); }
        else if (order != 2) throw invalid("H.264排序参数不合法");
        if (bits.ue() > 16) throw invalid("H.264参考帧数量超限");
        bits.read(1); long width = ((long) bits.ue() + 1) * 16, height = ((long) bits.ue() + 1) * 16;
        int frameOnly = bits.read(1);
        if (frameOnly == 0) { height *= 2; bits.read(1); }
        dimensions(width, height);
        bounds.width = Math.max(bounds.width, (int) width); bounds.height = Math.max(bounds.height, (int) height);
        bits.read(1);
        if (bits.read(1) == 1) {
            int left = bits.ue(8192, "裁剪参数"), right = bits.ue(8192, "裁剪参数"), top = bits.ue(8192, "裁剪参数"), bottom = bits.ue(8192, "裁剪参数");
            if ((long) (left + right) * 2 >= width || (long) (top + bottom) * 2 * (2 - frameOnly) >= height) throw invalid("H.264裁剪尺寸不合法");
        }
        if (bits.read(1) == 1) validateVui(bits);
        bits.trailing();
    }
    private static void validateVui(Bits bits) {
        if (bits.read(1) == 1 && bits.read(8) == 255) { bits.read(16); bits.read(16); }
        if (bits.read(1) == 1) bits.read(1);
        if (bits.read(1) == 1) { bits.read(4); if (bits.read(1) == 1) bits.read(24); }
        if (bits.read(1) == 1) { bits.ue(5, "色度位置"); bits.ue(5, "色度位置"); }
        if (bits.read(1) == 1) { bits.read(32); bits.read(32); bits.read(1); }
        boolean nalHrd = bits.read(1) == 1; if (nalHrd) validateHrd(bits);
        boolean vclHrd = bits.read(1) == 1; if (vclHrd) validateHrd(bits);
        if (nalHrd || vclHrd) bits.read(1);
        bits.read(1);
        if (bits.read(1) == 1) {
            bits.read(1); for (int i = 0; i < 4; i++) bits.ue(16, "视频码流限制");
            bits.ue(16, "排序帧数"); bits.ue(16, "解码缓存帧数");
        }
    }
    private static void validateHrd(Bits bits) {
        int count = bits.ue(31, "HRD缓存数量"); bits.read(8);
        for (int i = 0; i <= count; i++) { bits.ue(); bits.ue(); bits.read(1); }
        bits.read(20);
    }
    private static void validatePps(byte[] nal) {
        var bits = rbsp(nal, 8);
        bits.ue(255, "图像参数编号"); bits.ue(31, "序列参数编号"); bits.read(2);
        if (bits.ue() != 0) throw invalid("H.264暂不支持FMO分片组");
        bits.ue(15, "图像引用数量"); bits.ue(15, "图像引用数量"); bits.read(3);
        bits.se(); bits.se(); bits.se(); bits.read(3);
        if (bits.hasMoreRbspData()) {
            int transform = bits.read(1);
            if (bits.read(1) == 1) for (int i = 0; i < 6 + 2 * transform; i++) if (bits.read(1) == 1) scalingList(bits, i < 6 ? 16 : 64);
            bits.se();
        }
        bits.trailing();
    }
    private static void scalingList(Bits bits, int length) {
        int last = 8, next = 8;
        for (int j = 0; j < length; j++) { if (next != 0) next = (last + bits.se() + 256) & 255; last = next == 0 ? last : next; }
    }
    private ByteBuffer inspectFirstFrame(ByteBuffer packet, FrameBounds bounds) {
        var input = packet.duplicate();
        if (input.remaining() == 0 || input.remaining() > 8 * 1024 * 1024 + 65536) throw invalid("视频首帧长度不合法");
        byte[] bytes = new byte[input.remaining()]; input.get(bytes);
        var clean = new ByteArrayOutputStream(bytes.length); int units = 0, slices = 0;
        int start = startCode(bytes, 0);
        if (start != 0) throw invalid("视频首帧NAL起始标记不合法");
        while (start >= 0) {
            if (++units > 512) throw invalid("视频首帧NAL数量超限");
            int header = start + (bytes[start + 2] == 1 ? 3 : 4), end = startCode(bytes, header);
            if (end < 0) end = bytes.length;
            if (header >= end || (bytes[header] & 128) != 0) throw invalid("视频首帧NAL数据不合法");
            int type = bytes[header] & 31;
            if (type == 7 || type == 8) {
                if (end - header > 4096) throw invalid("视频首帧参数长度超限");
                byte[] parameter = java.util.Arrays.copyOfRange(bytes, header, end);
                if (type == 7) validateSps(parameter, bounds); else validatePps(parameter);
            } else if (type == 5) {
                var bits = rbsp(java.util.Arrays.copyOfRange(bytes, header, Math.min(end, header + 128)), 5);
                bits.ue((int) (maxPixels / 256), "首帧宏块位置"); int kind = bits.ue();
                if (kind != 2 && kind != 7) throw invalid("视频首帧必须使用独立IDR画面");
                bits.ue(255, "首帧图像参数编号"); slices++;
            } else if (!Set.of(6, 9, 10, 11, 12).contains(type)) throw invalid("视频必须以可独立解码的IDR画面开始");
            // Ancillary SEI/filler data is not necessary to prove the frame is decodable.
            if (type == 7 || type == 8 || type == 5) { clean.write(0); clean.write(0); clean.write(0); clean.write(1); clean.write(bytes, header, end - header); }
            start = end == bytes.length ? -1 : end;
        }
        if (slices == 0) throw invalid("视频没有可独立解码的首帧");
        return ByteBuffer.wrap(clean.toByteArray());
    }
    private static int startCode(byte[] bytes, int offset) {
        for (int i = offset; i + 2 < bytes.length; i++) if (bytes[i] == 0 && bytes[i + 1] == 0 && (bytes[i + 2] == 1 || (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1))) return i;
        return -1;
    }
    private void dimensions(long width, long height) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || width * height > maxPixels) throw invalid("视频分辨率超出允许像素上限");
    }
    private static BusinessException invalid(String message) { return new BusinessException("ATTACHMENT_CONTENT_INVALID", message); }
    private static final class FrameBounds { int width; int height; }
    private static final class Bits {
        final byte[] bytes; final int limit; int bit;
        Bits(byte[] bytes, int length) { this.bytes = bytes; this.limit = length * 8; }
        int read(int count) { if (bit + count > limit) throw invalid("H.264编码参数截断"); int value = 0; for (int i = 0; i < count; i++, bit++) value = value << 1 | (bytes[bit / 8] >> (7 - bit % 8) & 1); return value; }
        int ue() { int zeros = 0; while (read(1) == 0) if (++zeros > 24) throw invalid("H.264编码参数超限"); return (1 << zeros) - 1 + read(zeros); }
        int ue(int max, String field) { int value = ue(); if (value > max) throw invalid("H.264" + field + "超限"); return value; }
        int se() { int value = ue(); return (value & 1) == 0 ? -value / 2 : (value + 1) / 2; }
        boolean hasMoreRbspData() { if (limit - bit > 8) return true; int saved = bit; boolean more = read(1) != 1; while (bit < limit) more |= read(1) != 0; bit = saved; return more; }
        void trailing() { if (read(1) != 1 || limit - bit > 7) throw invalid("H.264参数尾部不合法"); while (bit < limit) if (read(1) != 0) throw invalid("H.264参数尾部不合法"); }
    }
}
