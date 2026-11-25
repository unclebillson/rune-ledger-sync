package com.runeledger.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

final class QuestProgressReader
{
    private static final Quest[] QUESTS = knownQuests();
    private static final int BATCH_SIZE = 10;

    private final JsonArray completed = new JsonArray();
    private int checked;

    private static Quest[] knownQuests()
    {
        Map<Integer, Quest> unique = new LinkedHashMap<>();
        for (Quest quest : Quest.values())
        {
            unique.putIfAbsent(quest.getId(), quest);
        }
        return unique.values().toArray(new Quest[0]);
    }

    // Run from a game tick because Quest.getState reads the client script stack.
    JsonObject readBatch(Function<Quest, QuestState> state)
    {
        int end = Math.min(checked + BATCH_SIZE, QUESTS.length);
        while (checked < end)
        {
            Quest quest = QUESTS[checked++];
            if (state.apply(quest) == QuestState.FINISHED)
            {
                completed.add(quest.getId());
            }
        }
        if (checked < QUESTS.length)
        {
            return null;
        }

        JsonObject result = new JsonObject();
        result.addProperty("total", checked);
        result.add("completed", completed);
        return result;
    }
}
