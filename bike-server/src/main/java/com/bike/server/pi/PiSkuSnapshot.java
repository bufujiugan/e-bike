package com.bike.server.pi;

/** Catalog values copied into each PI line; catalog edits cannot rewrite a saved document. */
public record PiSkuSnapshot(
        Long id, Long productModelId, String skuCode, String productName,
        String specification, String modelName) {}
