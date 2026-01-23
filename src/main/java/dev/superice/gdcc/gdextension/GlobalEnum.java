package dev.superice.gdcc.gdextension;

import java.util.List;

public record GlobalEnum(
        String name,
        boolean isBitfield,
        List<EnumValue> values
) { }
