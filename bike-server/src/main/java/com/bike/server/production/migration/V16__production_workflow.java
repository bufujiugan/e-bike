package com.bike.server.production.migration;

import javax.xml.parsers.DocumentBuilderFactory;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;

/** SQL 统一保存在 Mapper.xml；迁移只负责按版本执行结构定义。 */
public class V16__production_workflow extends BaseJavaMigration {
  @Override
  public Integer getChecksum() {
    try (var input = new ClassPathResource("mapper/ProductionSchemaMapper.xml").getInputStream()) {
      var checksum = new java.util.zip.CRC32();
      checksum.update(input.readAllBytes());
      return (int) checksum.getValue();
    } catch (java.io.IOException error) {
      throw new IllegalStateException("无法校验生产数据库迁移", error);
    }
  }

  @Override
  public void migrate(Context context) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    try (var input = new ClassPathResource("mapper/ProductionSchemaMapper.xml").getInputStream();
        var statement = context.getConnection().createStatement()) {
      var children = factory.newDocumentBuilder().parse(input).getDocumentElement().getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        var node = children.item(i);
        if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE)
          statement.execute(node.getTextContent().trim());
      }
    }
  }
}
