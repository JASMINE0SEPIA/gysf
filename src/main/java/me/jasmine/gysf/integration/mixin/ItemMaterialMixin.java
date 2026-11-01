package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.jasmine.gysf.material.MaterialTintRules;
import net.minecraft.client.color.item.GrassColorSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CuboidItemModelWrapper.class)
public abstract class ItemMaterialMixin {

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/color/item/ItemTintSource;calculate(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/entity/LivingEntity;)I"))
    private int gysf$materialTint(ItemTintSource source, ItemStack stack, ClientLevel level, LivingEntity owner, Operation<Integer> original) {
        if (source instanceof GrassColorSource || stack.getItem() instanceof BlockItem block && MaterialTintRules.decorative(block.getBlock())) return -1;
        return original.call(source, stack, level, owner);
    }
}
