package com.runeledger.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Skill;

final class SkillSamples
{
    private static final Skill[] SKILLS = Skill.values();
    // Each skill uses two adjacent entries. A zero level marks a skill omitted from the snapshot.
    private final int[] samples = new int[SKILLS.length * 2];

    boolean capture(Client client)
    {
        boolean hasSkills = false;
        for (int index = 0; index < SKILLS.length; index++)
        {
            Skill skill = SKILLS[index];
            int level = client.getRealSkillLevel(skill);
            int xp = client.getSkillExperience(skill);
            boolean valid = level > 0 && xp >= 0;
            samples[index * 2] = valid ? level : 0;
            samples[index * 2 + 1] = valid ? xp : 0;
            hasSkills |= valid;
        }
        return hasSkills;
    }

    boolean unchangedSince(int[] previous)
    {
        return Arrays.equals(samples, previous);
    }

    int[] copy()
    {
        return samples.clone();
    }

    static List<SnapshotTransport.SkillValue> entries(int[] snapshot)
    {
        List<SnapshotTransport.SkillValue> skills = new ArrayList<>(SKILLS.length);
        for (int index = 0; index < SKILLS.length; index++)
        {
            int level = snapshot[index * 2];
            if (level > 0)
            {
                skills.add(new SnapshotTransport.SkillValue(SKILLS[index].getName(), level, snapshot[index * 2 + 1]));
            }
        }
        return skills;
    }
}
