package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FTBQuestAccess {

    private static final Logger LOGGER =
            LogManager.getLogger("CointCoreGTO:DimensionAccess");

    private FTBQuestAccess() {
    }

    public static boolean isQuestCompleted(
            ServerPlayer player,
            String questIdHex
    ) {
        final long questId;

        try {
            questId = Long.parseUnsignedLong(
                    normalizeQuestId(questIdHex),
                    16
            );
        } catch (NumberFormatException exception) {
            LOGGER.error(
                    "Некорректный ID FTB-квеста: {}",
                    questIdHex,
                    exception
            );
            return false;
        }

        ServerQuestFile questFile = ServerQuestFile.INSTANCE;

        if (questFile == null) {
            LOGGER.warn(
                    "FTB Quests ещё не загрузил ServerQuestFile"
            );
            return false;
        }

        Quest quest = questFile.getQuest(questId);

        if (quest == null) {
            LOGGER.error(
                    "FTB Quests не нашёл квест с ID {}",
                    questIdHex
            );
            return false;
        }

        return FTBTeamsAPI.api()
                .getManager()
                .getTeamForPlayer(player)
                .map(team -> {
                    TeamData teamData =
                            questFile.getNullableTeamData(team.getId());

                    return teamData != null
                            && teamData.isCompleted(quest);
                })
                .orElse(false);
    }

    private static String normalizeQuestId(String value) {
        String result = value.trim();

        if (
                result.startsWith("0x")
                        || result.startsWith("0X")
        ) {
            result = result.substring(2);
        }

        return result;
    }
}