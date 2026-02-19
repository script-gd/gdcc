package dev.superice.gdcc.logger;

import org.jetbrains.annotations.NotNull;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

public final class GdccSlf4jServiceProvider implements SLF4JServiceProvider {
    /// Should stay non-final to follow SLF4J provider conventions.
    @SuppressWarnings("FieldMayBeFinal")
    public static String REQUESTED_API_VERSION = "2.0.17";

    private final ILoggerFactory loggerFactory = new GdccLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public @NotNull ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public @NotNull IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public @NotNull MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public @NotNull String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // Nothing to initialize for this in-memory provider.
    }
}
