package com.bike.server.vehicle;

import com.bike.server.common.BusinessException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** 生成整车二维码图片，二维码内容仍由整车档案中的业务码决定。 */
@Service
public class VehicleQrCodeService {
    private static final int MIN_SIZE = 128;
    private static final int MAX_SIZE = 1024;
    private final VehicleService vehicleService;

    public VehicleQrCodeService(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    public byte[] renderPng(long vehicleId, int requestedSize) {
        VehicleView vehicle = vehicleService.getById(vehicleId);
        if (vehicle.getVehicleQrCode() == null || vehicle.getVehicleQrCode().isBlank()) {
            throw new BusinessException("QR_NOT_AVAILABLE", "该整车尚未生成总二维码");
        }
        int size = Math.min(MAX_SIZE, Math.max(MIN_SIZE, requestedSize));
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(
                    vehicle.getVehicleQrCode(),
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", output);
                return output.toByteArray();
            }
        } catch (WriterException | IOException exception) {
            throw new BusinessException("QR_RENDER_FAILED", "二维码图片生成失败");
        }
    }
}
