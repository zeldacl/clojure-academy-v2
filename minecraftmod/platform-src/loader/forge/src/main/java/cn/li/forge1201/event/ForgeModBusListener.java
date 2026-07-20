package cn.li.forge1201.event;

import cn.li.forge1201.MyMod1201;
import cn.li.mc1201.datagen.DataGeneratorInterop;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeModBusListener {
    private ForgeModBusListener() {
    }

    @SubscribeEvent
    public static void onGatherData(GatherDataEvent event) {
        DataGeneratorInterop.invoke(
            "[my_mod] Error invoking Clojure DataGenerator handler: ",
            "cn.li.forge1201.datagen.setup",
            "static-gather-data",
            event);
    }
}
