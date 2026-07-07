package Crazer.cubeofinterest.cointcoregto.compat.jade.radio;

import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlock;
import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class CointRadioJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(
                CointRadioJadeProvider.INSTANCE,
                CointRadioBlockEntity.class
        );

        System.out.println("[CointCoreGTO Jade] Radio common provider registered");
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(
                CointRadioJadeProvider.INSTANCE,
                CointRadioBlock.class
        );

        System.out.println("[CointCoreGTO Jade] Radio client provider registered");
    }
}