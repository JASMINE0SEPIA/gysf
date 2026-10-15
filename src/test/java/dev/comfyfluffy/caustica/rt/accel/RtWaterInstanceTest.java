package dev.comfyfluffy.caustica.rt.accel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterInstanceTest {
    private static final float[] TRANSFORM={1,0,0,1001,0,1,0,-64,0,0,1,4352};

    @Test void sizeQueryAndBuildUseFourExplicitHomogeneousAabbGeometries() {
        try(var stack=MemoryStack.stackPush()) {
            var info=RtAccel.waterBuildInfo(stack,0x1000).get(0);
            assertEquals(4,info.geometryCount());
            assertEquals(org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,info.type());
            var geometry=info.pGeometries();assertNotNull(geometry);
            assertEquals(4,geometry.remaining());
            for(int i=0;i<4;i++) {
                assertEquals(org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_AABBS_KHR,geometry.get(i).geometryType());
                assertEquals(24,geometry.get(i).geometry().aabbs().stride());
                assertEquals(0x1000,geometry.get(i).geometry().aabbs().data().deviceAddress());
            }
            assertEquals(0,info.flags()&org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR);
        }
    }

    @Test void waterChildPreservesSectionIdentityAndFitsAnIndependentNativeInstance() {
        var section=new RtAccel.Instance(TRANSFORM,11,0xC00007,3,5,22);
        var legacy=new RtAccel.Instance(TRANSFORM,33,9);
        assertEquals(3,RtAccel.physicalInstanceCount(List.of(section,legacy)));
        assertEquals(1,legacy.physicalCount());
        assertThrows(IndexOutOfBoundsException.class,()->legacy.physicalAddress(1));
        assertThrows(IndexOutOfBoundsException.class,()->section.physicalAddress(2));

        // Inspect actual Vulkan 64-byte native records, including the nonzero destination offset.
        var bytes=ByteBuffer.allocateDirect(4*64).order(ByteOrder.nativeOrder());
        for(int i=0;i<bytes.capacity();i++)bytes.put(i,(byte)0x5a);
        RtAccel.writeTlasInstances(List.of(section),MemoryUtil.memAddress(bytes),1);
        for(int component=0;component<2;component++) {
            int start=(component+1)*64;
            for(int i=0;i<12;i++)assertEquals(TRANSFORM[i],bytes.getFloat(start+i*4));
            assertEquals(0x03C00007,bytes.getInt(start+48));
            assertEquals(component==0 ? 0x01000005 : 5,bytes.getInt(start+52));
            assertEquals(component==0 ? 11L : 22L,bytes.getLong(start+56));
        }
        assertEquals(0x5a,bytes.get(0));assertEquals(0x5a,bytes.get(192));
    }
}
