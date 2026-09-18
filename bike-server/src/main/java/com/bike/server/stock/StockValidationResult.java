package com.bike.server.stock;

public record StockValidationResult(boolean valid, String frameSn, Integer status, String message) {}
