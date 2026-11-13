package net.ledok.eden_ld.rental;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.SkillsAPI;

/**
 * Pufferfish Skills for the rental stash (only loaded when it is installed): every category's unlocked skills saved,
 * then reset and the category locked (no skill bonuses, nothing to spend points on), and back after. Experience,
 * level and points are never touched (activity levels and run scaling read them during the session; experience earned
 * meanwhile stays); they are saved in the record too, for staff.
 */
final class RentalSkills {

    private RentalSkills() {
    }

    /** Every category's state; nothing changes. */
    static CompoundTag save(ServerPlayer player) {
        CompoundTag saved = new CompoundTag();
        SkillsAPI.streamCategories().forEach(category -> {
            CompoundTag entry = new CompoundTag();
            entry.putBoolean("unlocked", category.isUnlocked(player));
            category.getExperience().ifPresent(experience -> entry.putInt("experience", experience.getTotal(player)));
            entry.putInt("extra_points", category.getExtraPoints(player));
            CompoundTag points = new CompoundTag();
            category.streamPointsSources(player).forEach(source -> points.putInt(source.toString(),
                    category.getPoints(player, source)));
            entry.put("points", points);
            ListTag skills = new ListTag();
            category.streamUnlockedSkills(player).forEach(skill -> skills.add(StringTag.valueOf(skill.getId())));
            entry.put("skills", skills);
            saved.put(category.getId().toString(), entry);
        });
        return saved;
    }

    /**
     * Every category's skills reset and the category locked.
     * <p>
     * Pufferfish Skills' "show category" packet holds the player's live category data and is encoded later on the
     * network thread; changing the skills right after one is sent (as {@code resetSkills} + {@code lock} did) can
     * throw a ConcurrentModificationException in the encoder, which kicks the player. So the category is hidden first
     * (that packet holds only the id) and each skill is locked on its own (those packets hold copies).
     */
    static void clearAll(ServerPlayer player) {
        SkillsAPI.streamCategories().forEach(category -> {
            category.lock(player);
            lockEvery(player, category);
        });
    }

    private static void lockEvery(ServerPlayer player, Category category) {
        List<Skill> unlocked = category.streamUnlockedSkills(player).toList();
        for (Skill skill : unlocked) {
            skill.lock(player);
        }
    }

    /** Puts every saved category's lock state and skills back. Categories and skills gone since are skipped. */
    static void giveBack(ServerPlayer player, CompoundTag saved) {
        for (String key : saved.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            Category category = id == null ? null : SkillsAPI.getCategory(id).orElse(null);
            if (category == null) {
                continue;
            }
            CompoundTag entry = saved.getCompound(key);
            // Hidden while it changes, shown once at the end (see clearAll): nothing changes after the show packet.
            category.lock(player);
            lockEvery(player, category);
            for (Tag skill : entry.getList("skills", Tag.TAG_STRING)) {
                category.getSkill(skill.getAsString()).ifPresent(found -> found.unlock(player));
            }
            if (entry.getBoolean("unlocked")) {
                category.unlock(player);
            }
        }
    }
}
