package dev.superice.gdcc.logger;

import org.slf4j.event.Level;

enum LogLevelStyle {
    DEBUG("DEBUG", "fg(white),faint"),
    INFO("INFO", "fg(blue)"),
    WARNING("WARNING", "fg(yellow)"),
    ERROR("ERROR", "fg(red)");

    private final String label;
    private final String ansiStyle;

    LogLevelStyle(String label, String ansiStyle) {
        this.label = label;
        this.ansiStyle = ansiStyle;
    }

    String label() {
        return label;
    }

    String ansiStyle() {
        return ansiStyle;
    }

    static LogLevelStyle fromSlf4jLevel(Level level) {
        return switch (level) {
            case ERROR -> ERROR;
            case WARN -> WARNING;
            case INFO -> INFO;
            case DEBUG, TRACE -> DEBUG;
        };
    }
}
