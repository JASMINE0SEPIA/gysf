package dev.comfyfluffy.caustica.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 26.3 retains skin render infos across reloads, while their borrowed texture views may be closed. */
@Mixin(targets="net.minecraft.client.renderer.PlayerSkinRenderCache$RenderInfo")
public abstract class PlayerSkinRenderInfoMixin {
    @Shadow private GpuTextureView textureView;

    @Inject(method="textureView",at=@At("HEAD"))
    private void caustica$refreshClosedSkinView(CallbackInfoReturnable<GpuTextureView> cir) {
        if(textureView!=null && (textureView.isClosed() || textureView.texture().isClosed())) {
            textureView=null;
            CausticaMod.LOGGER.info("Refreshing closed player skin view after resource reload");
        }
    }
}
