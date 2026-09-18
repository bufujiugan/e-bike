package com.bike.server.transfer;

import java.util.List;

public record ImportResult(int totalRows, int successRows, int failureRows, List<ImportFailure> failures) {
    public record ImportFailure(int rowNumber, String reason) { }
}
