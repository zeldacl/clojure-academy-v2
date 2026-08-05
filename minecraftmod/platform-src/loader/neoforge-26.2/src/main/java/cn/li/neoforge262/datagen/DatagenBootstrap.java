package cn.li.neoforge262.datagen;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Minimal 26.2 datagen bootstrap so {@code runData} produces a non-empty
 * hash manifest while full providers are still being ported.
 */
public final class DatagenBootstrap {
    private DatagenBootstrap() {
    }

    public static void onGatherData(GatherDataEvent event) {
        PackOutput packOutput = event.getGenerator().getPackOutput();
        event.addProvider(new DataProvider() {
            @Override
            public CompletableFuture<?> run(CachedOutput output) {
                Path path = packOutput
                        .getOutputFolder(PackOutput.Target.DATA_PACK)
                        .resolve("academy")
                        .resolve("academy_datagen_bootstrap.json");
                JsonObject json = new JsonObject();
                json.addProperty("mod", "academy");
                json.addProperty("target", "neoforge-26.2");
                json.addProperty("status", "bootstrap");
                return DataProvider.saveStable(output, json, path);
            }

            @Override
            public String getName() {
                return "academy_datagen_bootstrap";
            }
        });
    }
}
