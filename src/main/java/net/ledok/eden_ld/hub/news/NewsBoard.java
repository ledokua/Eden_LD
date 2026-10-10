package net.ledok.eden_ld.hub.news;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenData;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.compat.PlaceholdersCompat;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.MailText;
import net.ledok.eden_ld.hub.HubServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The Home tab's news: Markdown text in one or more languages (readers see theirs, else the
 * default), written in the Moderation window's News section by staff with
 * {@code eden_ld.admin.news} and published to everyone online at once. Kept in the world's data.
 */
public final class NewsBoard extends SavedData {

    private static final Factory<NewsBoard> FACTORY = new Factory<>(NewsBoard::new, NewsBoard::load, null);
    private static final int MAX_LANGUAGES = 20;
    private static final int MAX_LENGTH = 8000;

    private String defaultLanguage = "en_us";
    private final List<MailText> texts = new ArrayList<>();
    private long updatedAt;
    private String updatedBy = "";

    public static NewsBoard get(MinecraftServer server) {
        return EdenData.get(server, FACTORY, "news");
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.NewsSave.TYPE,
                (payload, context) -> context.server().execute(() -> save(context.player(), payload)));
    }

    /** As staff edit it (raw, placeholders unfilled). */
    public HubPayloads.NewsView view() {
        return new HubPayloads.NewsView(defaultLanguage, List.copyOf(texts), updatedAt, updatedBy);
    }

    /** As a reader gets it: every language, their placeholders filled in (the client picks its language). */
    public HubPayloads.NewsView forReader(ServerPlayer reader) {
        List<MailText> filled = new ArrayList<>();
        for (MailText text : texts) {
            filled.add(new MailText(text.language(), "", PlaceholdersCompat.fill(text.body(), reader)));
        }
        return new HubPayloads.NewsView(defaultLanguage, filled, updatedAt, "");
    }

    private static void save(ServerPlayer player, HubPayloads.NewsSave payload) {
        if (!LuckPermsCompat.hasPermission(player, EdenPermissions.NEWS)) {
            return;
        }
        NewsBoard board = get(player.server);
        board.texts.clear();
        for (MailText text : payload.texts()) {
            if (board.texts.size() >= MAX_LANGUAGES || text.language().isBlank()
                    || board.texts.stream().anyMatch(t -> t.language().equals(text.language()))) {
                continue;
            }
            String body = text.body().length() > MAX_LENGTH ? text.body().substring(0, MAX_LENGTH) : text.body();
            board.texts.add(new MailText(text.language(), "", body));
        }
        board.defaultLanguage = board.texts.stream().anyMatch(t -> t.language().equals(payload.defaultLanguage()))
                ? payload.defaultLanguage() : board.texts.isEmpty() ? "en_us" : board.texts.get(0).language();
        board.updatedAt = System.currentTimeMillis() / 1000L;
        board.updatedBy = player.getGameProfile().getName();
        board.setDirty();
        EdenLD.LOGGER.info("{} published the news", board.updatedBy);
        ServerPlayNetworking.send(player, new HubPayloads.ModNotice(
                Component.translatable("message.eden_ld.moderation.news_published"), true));
        HubServer.refreshHomeForAll(player.server);
    }

    private static NewsBoard load(CompoundTag tag, HolderLookup.Provider registries) {
        NewsBoard board = new NewsBoard();
        board.defaultLanguage = tag.getString("default");
        ListTag list = tag.getList("texts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag text = list.getCompound(i);
            board.texts.add(new MailText(text.getString("language"), "", text.getString("body")));
        }
        board.updatedAt = tag.getLong("updated");
        board.updatedBy = tag.getString("by");
        return board;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("default", defaultLanguage);
        ListTag list = new ListTag();
        for (MailText text : texts) {
            CompoundTag entry = new CompoundTag();
            entry.putString("language", text.language());
            entry.putString("body", text.body());
            list.add(entry);
        }
        tag.put("texts", list);
        tag.putLong("updated", updatedAt);
        tag.putString("by", updatedBy);
        return tag;
    }
}
