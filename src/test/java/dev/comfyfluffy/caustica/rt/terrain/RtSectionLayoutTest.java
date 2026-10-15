package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.gen.SectionData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtSectionLayoutTest {
    @Test void reflectedWaterPointerAndArrayStrideMatchBothNearAndLodTables() {
        // std430: two u64 pointers, four u32 bucket bases, then one u64 water pointer.
        assertEquals(40,SectionData.BYTE_SIZE);
        assertEquals(SectionData.BYTE_SIZE,RtSectionTable.SECTION_ENTRY_BYTES);
        var bytes=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        new SectionData(11,22,new int[]{3,5,7,9},33).write(bytes);
        new SectionData(44,55,new int[]{13,15,17,19},0).write(bytes.slice(40,40).order(ByteOrder.nativeOrder()));
        assertEquals(11,bytes.getLong(0));assertEquals(22,bytes.getLong(8));
        assertEquals(9,bytes.getInt(28));assertEquals(33,bytes.getLong(32));
        assertEquals(44,bytes.getLong(40));assertEquals(55,bytes.getLong(48));
        assertEquals(19,bytes.getInt(68));assertEquals(0,bytes.getLong(72));
    }
}
