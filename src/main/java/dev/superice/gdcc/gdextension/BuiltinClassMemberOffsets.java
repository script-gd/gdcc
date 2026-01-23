package dev.superice.gdcc.gdextension;

import java.util.List;

public record BuiltinClassMemberOffsets(
        String buildConfiguration,
        List<ClassMemberData> classes
) {
    public record ClassMemberData(
            String name,
            List<MemberOffsetData> members
    ) {}

    public record MemberOffsetData(
            String member,
            int offset,
            String meta
    ) {}
}
