package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain.Tile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterFieldImageTest {
    @Test void exportProductionImagesForIndependentShaderProbes() throws Exception {
        var current=new java.util.ArrayList<Tile>();var previous=new java.util.ArrayList<Tile>();
        var fullCell=new java.util.ArrayList<Tile>();
        for(boolean border:new boolean[]{false,true}) {
            int baseX=border?1875000:0,baseZ=border?-1875000:0,range=border?1:2;
            for(int tx=baseX-range;tx<=baseX+range;tx++)for(int tz=baseZ-range;tz<=baseZ+range;tz++) {
                short[] codes=new short[256];long[] known=new long[4];
                for(int n=0;n<256;n++) {
                    int x=tx*16+(n&15),z=tz*16+(n>>>4);
                    codes[n]=(short)(border?Math.floorMod(x,16)*4096:x<=0?0:x==1?6554:65535);
                    if(border?(Math.floorMod(x,16)!=7&&Math.floorMod(z,16)!=7):(x!=7&&z!=7))known[n>>>6]|=1L<<(n&63);
                }
                boolean same=true;for(short code:codes)same&=code==codes[0];
                current.add(new Tile(tx,175,tz,1,same?new short[]{codes[0]}:codes,known));
                fullCell.add(new Tile(tx,176,tz,1,same?new short[]{codes[0]}:codes,known));
                previous.add(new Tile(tx,175,tz,1,new short[]{0},new long[]{-1,-1,-1,-1}));
                previous.add(new Tile(tx,176,tz,1,new short[]{0},new long[]{-1,-1,-1,-1}));
            }
        }
        var dir=java.nio.file.Path.of("build/shader-test");java.nio.file.Files.createDirectories(dir);
        for(boolean old:new boolean[]{false,true}) {
            int[] words=RtWaterFieldImage.pack(old?previous:current);
            var bytes=java.nio.ByteBuffer.allocate(words.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.asIntBuffer().put(words);
            java.nio.file.Files.write(dir.resolve(old?"water-field-previous.bin":"water-field-current.bin"),bytes.array());
        }
        int[] fullWords=RtWaterFieldImage.pack(fullCell);
        var fullBytes=java.nio.ByteBuffer.allocate(fullWords.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        fullBytes.asIntBuffer().put(fullWords);
        java.nio.file.Files.write(dir.resolve("water-field-full-cell.bin"),fullBytes.array());
    }
    private static int word(int[] image,int x,int y,int z) {
        // Deliberately scan stored coordinates rather than using the writer's hash/probe algorithm.
        for(int e=4;e<4+(image[0]+1)*8;e+=8)
            if(image[e+3]!=0&&image[e]==Math.floorDiv(x,16)&&image[e+1]==y&&image[e+2]==Math.floorDiv(z,16))
                return image[image[e+3]+(image[e+4]==1?0:Math.floorMod(z,16)*16+Math.floorMod(x,16))];
        return 0;
    }
    @Test void knownMasksSurviveConstantAndMixedPacking() {
        var known=new Tile(-1,175,0,1,new short[]{-1},new long[]{-1,-1,-1,-1});
        var partial=new Tile(0,175,0,1,new short[]{12345},new long[]{1,0,0,Long.MIN_VALUE});
        var unknown=new Tile(1,175,0,1,new short[]{-1},new long[4]);
        int[] data=RtWaterFieldImage.pack(List.of(known,partial,unknown));
        assertEquals(data.length,data[3]);assertEquals(1,data[2]);assertEquals(3,data[1]);
        assertEquals(131071,word(data,-1,175,0));
        assertEquals(65536|12345,word(data,0,175,0));assertEquals(12345,word(data,1,175,0));
        assertEquals(65536|12345,word(data,15,175,15));
        assertEquals(65535,word(data,16,175,0));assertEquals(0,word(data,32,175,0));
        assertEquals(4+8*8+258,data.length);
    }
    @Test void signedWorldCoordinatesRoundTripWithoutFloatConversion() {
        for(int x:new int[]{-1875000,1874999,-1,0})for(int y:new int[]{-4096,-1,0,4096})
            for(int z:new int[]{-1875000,1874999,-1,0}) {
                long key=Tile.key(x,y,z);
                assertEquals(x,RtWaterFieldImage.x(key));assertEquals(y,RtWaterFieldImage.y(key));assertEquals(z,RtWaterFieldImage.z(key));
            }
    }
    @Test void collisionsAndMissingCoordinatesDoNotAlias() {
        var list=new java.util.ArrayList<Tile>();
        for(int x=-64;x<64;x++)list.add(new Tile(x,-1,-x,1,new short[]{(short)(x+64)},new long[]{-1,-1,-1,-1}));
        int[] data=RtWaterFieldImage.pack(list);
        for(int x=-64;x<64;x++)assertEquals(65536+x+64,word(data,x*16,-1,-x*16));
        assertEquals(0,word(data,0,1,0));
        assertThrows(IllegalArgumentException.class,()->RtWaterFieldImage.pack(List.of(list.getFirst(),list.getFirst())));
        assertThrows(IllegalArgumentException.class,()->RtWaterFieldImage.pack(java.util.Collections.nCopies(4097,list.getFirst())));
    }
}
