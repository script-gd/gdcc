package dev.superice.gdcc.backend.c;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Test;

public class FindZigTest {
    @Test
    public void findZigTest() {
        var result = ZigUtil.findZig();
        System.out.println(result);
    }
}
