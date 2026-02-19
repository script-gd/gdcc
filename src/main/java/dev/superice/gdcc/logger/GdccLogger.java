package dev.superice.gdcc.logger;

import picocli.CommandLine.Help.Ansi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serial;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class GdccLogger extends LegacyAbstractLogger {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final String LOGGER_CLASS_NAME = GdccLogger.class.getName();
    private static final String TIME_STYLE = "fg(cyan)";
    private static final String LOGGER_NAME_STYLE = "fg(white),faint";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object OUTPUT_LOCK = new Object();

    public GdccLogger(@NotNull String loggerName) {
        this.name = loggerName;
    }

    @Override
    protected @NotNull String getFullyQualifiedCallerName() {
        return LOGGER_CLASS_NAME;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            @NotNull Level level,
            @Nullable Marker marker,
            @Nullable String messagePattern,
            @Nullable Object[] arguments,
            @Nullable Throwable throwable
    ) {
        var levelStyle = LogLevelStyle.fromSlf4jLevel(level);
        var content = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        if (content == null) {
            content = "null";
        }

        var timeText = LocalTime.now().format(TIME_FORMATTER);
        var line = colorize(TIME_STYLE, timeText)
                + " " + colorize(levelStyle.ansiStyle(), "[" + levelStyle.label() + "]")
                + " " + colorize(LOGGER_NAME_STYLE, "[" + name + "]")
                + " " + content;

        synchronized (OUTPUT_LOCK) {
            System.out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    private static @NotNull String colorize(@NotNull String style, @NotNull String text) {
        return Ansi.ON.string("@|" + style + " " + text + "|@");
    }
}
