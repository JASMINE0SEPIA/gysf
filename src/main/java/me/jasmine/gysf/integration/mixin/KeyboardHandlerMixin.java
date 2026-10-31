package me.jasmine.gysf.integration.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import me.jasmine.gysf.debug.WorldDepthDebug;
import me.jasmine.gysf.integration.minecraft.SurfaceDebugAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void gysf$toggleDepthDebug(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (handle == minecraft.getWindow().handle() && action == InputConstants.PRESS
            && minecraft.level != null && minecraft.gui.screen() == null) {
            var surface = ((SurfaceDebugAccess) minecraft.gameRenderer).gysf$surfaceDebug();
            if (event.key() == InputConstants.KEY_F8) {
                surface.disable();
                WorldDepthDebug.toggle();
            } else if (event.key() == InputConstants.KEY_F7) {
                WorldDepthDebug.disable();
                surface.cycle();
            }
        }
    }
}
