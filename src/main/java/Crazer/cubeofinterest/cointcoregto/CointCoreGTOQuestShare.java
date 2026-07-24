package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftbquests.quest.Quest;

public final class CointCoreGTOQuestShare {
    private CointCoreGTOQuestShare() {
    }

    public static void sendToServer(Quest quest, ItemShareChannel channel) {
        CointCoreGTOItemShare.sendQuestToServer(quest, channel);
    }
}
