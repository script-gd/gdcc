open module gdcc {
    requires com.google.gson;
    requires freemarker;
    requires info.picocli;
    requires java.xml;
    requires org.slf4j;
    requires org.jetbrains.annotations;
    requires jAstyle;
    requires gdparser;

    provides org.slf4j.spi.SLF4JServiceProvider with dev.superice.gdcc.logger.GdccSlf4jServiceProvider;
}
