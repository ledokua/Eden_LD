package net.ledok.eden_ld.world;

import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One dimension's gamerules: the rules it changes, over the server-wide rules for everything else.
 * {@code Level#getGameRules} returns it for that dimension (see {@code LevelDimensionMixin}); one object per
 * dimension for the whole server run, changed in place.
 * <p>
 * Rules the dimension doesn't change are answered by the server-wide object itself, so mods that hook that
 * object keep working. Open Parties and Claims protects claims from mob griefing only when the object it
 * is asked through <i>is</i> the server's ({@code MixinFabricGameRules}); for a changed mobGriefing the
 * server's object answers, with this dimension's value put in for the moment.
 */
public final class DimensionGameRules extends GameRules {

    private final MinecraftServer server;
    private final GameRules serverRules;
    /** The rules this dimension changes; their values live in this object's own rule map. */
    private final Set<Key<?>> changed = ConcurrentHashMap.newKeySet();

    DimensionGameRules(MinecraftServer server, GameRules serverRules) {
        this.server = server;
        this.serverRules = serverRules;
    }

    /** The server-wide rules under this dimension's changes. */
    public GameRules serverRules() {
        return serverRules;
    }

    public boolean isChanged(Key<?> key) {
        return changed.contains(key);
    }

    public Set<Key<?>> changed() {
        return Collections.unmodifiableSet(changed);
    }

    /** Changes a rule for this dimension, from its text form (as {@code /gamerule} and the file write it). */
    <T extends Value<T>> void set(Key<T> key, String text) {
        // No server: no change callbacks (vanilla's message every player about a server-wide change).
        super.getRule(key).setFrom(parse(key, text), null);
        changed.add(key);
    }

    /** A rule value read from its text form, the way the world save reads it (no callbacks run). */
    static <T extends Value<T>> T parse(Key<T> key, String text) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key.getId(), text);
        return new GameRules(new Dynamic<>(NbtOps.INSTANCE, tag)).getRule(key);
    }

    void clear() {
        changed.clear();
    }

    /** The changed rule whose value object {@code value} is (a mod wrote through this dimension's rules), or null. */
    @Nullable
    Key<?> keyOf(Value<?> value) {
        for (Key<?> key : changed) {
            if (own(key) == value) {
                return key;
            }
        }
        return null;
    }

    /** This dimension's value object of a rule (meaningful when it changes the rule). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Value<?> own(Key<?> key) {
        return super.getRule((Key) key);
    }

    @Override
    public <T extends Value<T>> T getRule(Key<T> key) {
        return changed.contains(key) ? super.getRule(key) : serverRules.getRule(key);
    }

    @Override
    public boolean getBoolean(Key<BooleanValue> key) {
        if (!changed.contains(key)) {
            return serverRules.getBoolean(key);
        }
        if (key == RULE_MOBGRIEFING && server.isSameThread()) {
            return asServer(key);
        }
        return super.getBoolean(key);
    }

    @Override
    public int getInt(Key<IntegerValue> key) {
        return changed.contains(key) ? super.getInt(key) : serverRules.getInt(key);
    }

    /** The server-wide object's answer, given this dimension's value. Server thread only. */
    private boolean asServer(Key<BooleanValue> key) {
        boolean own = super.getRule(key).get();
        BooleanValue shared = serverRules.getRule(key);
        boolean serverValue = shared.get();
        if (serverValue == own) {
            return serverRules.getBoolean(key);
        }
        shared.set(own, null);
        try {
            return serverRules.getBoolean(key);
        } finally {
            shared.set(serverValue, null);
        }
    }

    @Override
    public CompoundTag createTag() {
        CompoundTag tag = serverRules.createTag();
        for (Key<?> key : changed) {
            tag.putString(key.getId(), own(key).serialize());
        }
        return tag;
    }

    @Override
    public GameRules copy() {
        return new GameRules(new Dynamic<>(NbtOps.INSTANCE, createTag()));
    }
}
