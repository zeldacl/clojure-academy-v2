package cn.li.forge1201.sound;

import cn.li.forge1201.MyMod1201;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    private ModSounds() {
    }

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MyMod1201.MODID);

    public static final RegistryObject<SoundEvent> EM_RAILGUN = register("em.railgun");
    public static final RegistryObject<SoundEvent> EM_INTENSIFY_ACTIVATE = register("em.intensify_activate");
    public static final RegistryObject<SoundEvent> EM_INTENSIFY_LOOP = register("em.intensify_loop");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MyMod1201.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
