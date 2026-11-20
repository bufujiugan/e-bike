package com.bike.server.production;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductionMigrationConfig {
  @Bean
  public com.bike.server.production.migration.V59__sales_document_routing salesDocumentRoutingMigration() {
    return new com.bike.server.production.migration.V59__sales_document_routing();
  }
  @Bean
  public com.bike.server.production.migration.V58__europe_sales europeSalesMigration() {
    return new com.bike.server.production.migration.V58__europe_sales();
  }
  @org.springframework.context.annotation.Bean
  public com.bike.server.production.migration.V57__unified_fulfillment unifiedFulfillmentMigration() {
    return new com.bike.server.production.migration.V57__unified_fulfillment();
  }
  @Bean
  public com.bike.server.production.migration.V16__production_workflow
      productionWorkflowMigration() {
    return new com.bike.server.production.migration.V16__production_workflow();
  }
}
