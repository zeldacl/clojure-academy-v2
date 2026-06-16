package cn.li.fabric1201.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerPersistentDataMixin implements PlayerPersistentDataAccess {
    private static final String PERSISTENT_KEY = "my_mod_persistent";

    @Unique
    private CompoundTag my_mod$persistentData;

    @Override
    public CompoundTag my_mod$getPersistentData() {
        if (my_mod$persistentData == null) {
            my_mod$persistentData = new CompoundTag();
        }
        return my_mod$persistentData;
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void my_mod$readPersistentData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(PERSISTENT_KEY)) {
            my_mod$persistentData = tag.getCompound(PERSISTENT_KEY);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void my_mod$writePersistentData(CompoundTag tag, CallbackInfo ci) {
        if (my_mod$persistentData != null && !my_mod$persistentData.isEmpty()) {
            tag.put(PERSISTENT_KEY, my_mod$persistentData);
        }
    }
}
