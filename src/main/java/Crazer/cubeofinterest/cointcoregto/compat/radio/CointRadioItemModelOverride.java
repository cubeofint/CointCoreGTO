package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class CointRadioItemModelOverride {
    private CointRadioItemModelOverride() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation radioItemModel =
                new ModelResourceLocation(CointCoreGTO.MODID, "coint_radio", "inventory");

        ModelResourceLocation noteBlockItemModel =
                new ModelResourceLocation("minecraft", "note_block", "inventory");

        BakedModel vanillaModel = event.getModels().get(noteBlockItemModel);

        if (vanillaModel == null) {
            System.out.println("[CointMusic] Failed to find vanilla note_block item model");
            return;
        }

        event.getModels().put(radioItemModel, vanillaModel);
    }
}