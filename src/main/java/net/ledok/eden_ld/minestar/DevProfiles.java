package net.ledok.eden_ld.minestar;

import net.fabricmc.loader.api.FabricLoader;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Development only: a made-up Minestar profile for every player when a dev run starts with
 * {@code -Deden_ld.devProfiles=true}, so the profile UI (level ring, rewards, level rewards) can be
 * seen without the platform. Never active outside a development environment.
 */
final class DevProfiles {

    static final boolean ENABLED = FabricLoader.getInstance().isDevelopmentEnvironment()
            && Boolean.getBoolean("eden_ld.devProfiles");

    private DevProfiles() {
    }

    static ProfileSnapshot fake(UUID uuid) {
        List<ProfileSnapshot.Reward> rewards = List.of(
                new ProfileSnapshot.Reward(1, "First Steps", "Played your first hour on a Minestar server.",
                        "https://cdn.discordapp.com/emojis/1227381664649318541.png?size=64", 0),
                new ProfileSnapshot.Reward(2, "Explorer", "Visited every dimension.", "", 1),
                new ProfileSnapshot.Reward(3, "Dungeon Delver", "Cleared ten dungeons with a party.", "", 2),
                new ProfileSnapshot.Reward(4, "Legend of Eden", "Reached the top of the Fallen Eden season ladder.", "", 3),
                new ProfileSnapshot.Reward(5, "Supporter", "Had Prime for a whole year.", "", 2));
        long registered = LocalDate.of(2024, 6, 13).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        return new ProfileSnapshot("Dev", "Administrator", 0xE05050, true, registered, 421L * 3600,
                12, 25_712, 712, 2_500, 1_788, rewards, Set.of());
    }
}
