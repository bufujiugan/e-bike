package com.bike.server.assembly;

public record AssemblyValidationResult(boolean valid, String partSn, String partType, String status, String message) {
}
