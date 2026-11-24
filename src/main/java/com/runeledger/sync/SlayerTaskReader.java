package com.runeledger.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

final class SlayerTaskReader
{
    // These values match RuneLite's Slayer plugin and are not gameval constants.
    private static final int MODIFIER_TASK_SIZE = 2;
    private static final int BOSS_TASK_ID = 98;
    private static final int MAX_COUNT = 65535;

    private SlayerTaskReader()
    {
    }

    // Java null means that the task was not observed. JsonNull means no task is active.
    static JsonElement read(Client client)
    {
        int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        if (remaining == 0)
        {
            return JsonNull.INSTANCE;
        }
        if (remaining < 0 || remaining > MAX_COUNT)
        {
            return null;
        }

        Integer taskRow = taskRow(client);
        String name = gameText(field(client, taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE), 100);
        if (name == null)
        {
            return null;
        }

        int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
        String location = null;
        if (area > 0)
        {
            Integer areaRow = row(client, DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, area);
            location = gameText(field(client, areaRow, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER), 120);
            if (location == null)
            {
                // A restricted task must not become unrestricted when its location cannot be resolved.
                return null;
            }
        }

        long initial = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
        if (initial > 0 && client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == MODIFIER_TASK_SIZE)
        {
            int modifier = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
            initial += client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1 ? -(long) modifier : modifier;
        }
        JsonObject task = new JsonObject();
        task.addProperty("task", name);
        task.addProperty("remaining", remaining);
        task.addProperty("initial", initial >= remaining && initial <= MAX_COUNT ? (int) initial : null);
        task.addProperty("location", location);
        return task;
    }

    private static Integer taskRow(Client client)
    {
        int target = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
        if (target != BOSS_TASK_ID)
        {
            return row(client, DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, target);
        }
        int boss = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
        Integer bossRow = row(client, DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, boss);
        Object value = field(client, bossRow, DBTableID.SlayerTaskSublist.COL_TASK);
        return value instanceof Integer ? (Integer) value : null;
    }

    private static Integer row(Client client, int table, int column, int id)
    {
        List<Integer> rows = client.getDBRowsByValue(table, column, 0, id);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static Object field(Client client, Integer row, int column)
    {
        if (row == null)
        {
            return null;
        }
        Object[] values = client.getDBTableField(row, column, 0);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static String gameText(Object value, int maxLength)
    {
        if (!(value instanceof String))
        {
            return null;
        }
        String text = ((String) value).replace('\u00a0', ' ').trim();
        return text.isEmpty() || text.length() > maxLength || text.indexOf('<') >= 0 || text.indexOf('>') >= 0
            || text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT) ? null : text;
    }
}
