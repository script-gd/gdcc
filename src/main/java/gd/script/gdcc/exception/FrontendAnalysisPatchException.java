package gd.script.gdcc.exception;

import org.jetbrains.annotations.NotNull;

/// Exception thrown when a segmented frontend analysis patch violates publication contracts.
public final class FrontendAnalysisPatchException extends GdccException {
    public FrontendAnalysisPatchException(@NotNull String message) {
        super(message);
    }
}
