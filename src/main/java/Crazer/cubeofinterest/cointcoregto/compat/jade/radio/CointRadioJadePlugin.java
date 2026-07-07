package Crazer.cubeofinterest.cointcoregto.compat.jade.radio;

import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlock;
import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlockEntity;
import net.minecraftforge.fml.ModList;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class CointRadioJadePlugin implements IWailaPlugin {
    private static final String JADE_MOD_ID = "jade";

    @Override
    public void register(IWailaCommonRegistration registration) {
        if (!ModList.get().isLoaded(JADE_MOD_ID)) {
            return;
        }

        registration.registerBlockDataProvider(
                CointRadioJadeProvider.INSTANCE,
                CointRadioBlockEntity.class
        );

        System.out.println("[CointCoreGTO Jade] Radio common provider registered");
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        if (!ModList.get().isLoaded(JADE_MOD_ID)) {
            return;
        }

        registration.registerBlockComponent(
                CointRadioJadeProvider.INSTANCE,
                CointRadioBlock.class
        );

        System.out.println("[CointCoreGTO Jade] Radio client provider registered");
    }
}