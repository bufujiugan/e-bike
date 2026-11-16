package com.bike.server.production.migration;

import javax.xml.parsers.DocumentBuilderFactory;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;

/** 结构 SQL 保存在 Mapper.xml，迁移只按版本执行。 */
public class V58__europe_sales extends BaseJavaMigration {
    private static final String RESOURCE = "mapper/EuropeSalesSchemaMapper.xml";
    @Override public Integer getChecksum() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            var checksum = new java.util.zip.CRC32();
            checksum.update(input.readAllBytes());
            return (int) checksum.getValue();
        } catch (java.io.IOException error) { throw new IllegalStateException("无法校验欧洲销售迁移", error); }
    }
    @Override public void migrate(Context context) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (var input = new ClassPathResource(RESOURCE).getInputStream(); var statement = context.getConnection().createStatement()) {
            var children = factory.newDocumentBuilder().parse(input).getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                var node = children.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) statement.execute(node.getTextContent().trim());
            }
        }
    }
}
