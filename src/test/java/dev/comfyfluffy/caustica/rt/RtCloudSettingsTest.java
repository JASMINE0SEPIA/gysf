package dev.comfyfluffy.caustica.rt;

import net.minecraft.client.CloudStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtCloudSettingsTest {
    @Test void eitherPlayersCloudSwitchCanDisableTheWholeEffect() {
        for (var status : CloudStatus.values()) assertFalse(RtCloudSettings.enabled(false,status));
        assertFalse(RtCloudSettings.enabled(true,CloudStatus.OFF));
        assertTrue(RtCloudSettings.enabled(true,CloudStatus.FAST));
        assertTrue(RtCloudSettings.enabled(true,CloudStatus.FANCY));
    }
    @Test void classicCloudsKeepVanillasSheetAndBoxDepthForEveryVolumetricSliderValue() {
        for (float slider : new float[]{0f,.5f,1f}) {
            assertEquals(0f,RtCloudSettings.thickness(false,CloudStatus.OFF,slider));
            assertEquals(0f,RtCloudSettings.thickness(false,CloudStatus.FAST,slider));
            assertEquals(4f,RtCloudSettings.thickness(false,CloudStatus.FANCY,slider));
        }
    }
    @Test void volumetricDepthRemainsAdjustableAndOffStillWins() {
        assertEquals(0f,RtCloudSettings.thickness(true,CloudStatus.OFF,1f));
        assertEquals(0f,RtCloudSettings.thickness(true,CloudStatus.FANCY,0f));
        assertEquals(55f,RtCloudSettings.thickness(true,CloudStatus.FANCY,.5f));
        assertEquals(110f,RtCloudSettings.thickness(true,CloudStatus.FANCY,1f));
    }
}
