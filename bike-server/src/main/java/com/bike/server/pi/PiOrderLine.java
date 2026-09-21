package com.bike.server.pi;

public record PiOrderLine(Long productModelId, int quantity, int shippedQuantity) {}
