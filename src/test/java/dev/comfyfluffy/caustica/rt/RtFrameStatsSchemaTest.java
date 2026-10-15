package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Every literal instrumentation call must exist in the CSV schema, even on optional render paths. */
final class RtFrameStatsSchemaTest {
    @Test void allLiteralStageAndCounterNamesAreRegistered() throws Exception {
        var root=Path.of("src/main/java");
        String schema=Files.readString(root.resolve("dev/comfyfluffy/caustica/rt/RtFrameStats.java"));
        var call=Pattern.compile("RtFrameStats\\.FRAME\\.(?:stage|count|endStage)\\(\"([^\"]+)\"");
        int checked=0;
        try(var files=Files.walk(root)) {
            for(var file:files.filter(p->p.toString().endsWith(".java")).toList()) {
                var match=call.matcher(Files.readString(file));
                while(match.find()) {
                    String name=match.group(1);
                    assertTrue(schema.contains('"'+name+'"'),()->"Unregistered frame stat "+name+" in "+file);
                    checked++;
                }
            }
        }
        assertTrue(checked>50,"Instrumentation source scan found too few call sites");
    }
}
