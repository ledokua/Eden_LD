package net.ledok.eden_ld.hub.mail;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.ledok.eden_ld.EdenLD;
import net.ledok.eden_ld.command.EdenPermissions;
import net.ledok.eden_ld.compat.LuckPermsCompat;
import net.ledok.eden_ld.compat.PlaceholdersCompat;
import net.ledok.eden_ld.hub.HubData;
import net.ledok.eden_ld.hub.HubPayloads;
import net.ledok.eden_ld.hub.HubPayloads.HomeData;
import net.ledok.eden_ld.hub.HubPayloads.MailDraftData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The server side of the Moderation window's mail: drafts (saved per admin in the hub data, so
 * they survive leaving and restarting), checking and sending a draft (players by name, everyone,
 * or LuckPerms groups' direct members at send time), and the log of sent staff mail with recall.
 * Writing needs {@code eden_ld.admin.mail}; everyone's log {@code eden_ld.admin.mail.log}.
 */
public final class MailStaff {

    private static final String DRAFTS = "mail_drafts";
    private static final int MAX_DRAFTS = 30;
    private static final int LOG_SIZE = 200;
    private static final String DEFAULT_SENDER = "Administration";

    private MailStaff() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.ModOpen.TYPE,
                (payload, context) -> context.server().execute(() -> sendInfo(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.DraftSave.TYPE,
                (payload, context) -> context.server().execute(() -> saveDraft(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.MailSend.TYPE,
                (payload, context) -> context.server().execute(() -> send(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(HubPayloads.MailLogRequest.TYPE,
                (payload, context) -> context.server().execute(() -> log(context.player(), payload)));
    }

    /** The admin's rights, with mail left out when Economy_LD (which stores it) is missing. */
    private static int staff(ServerPlayer player) {
        int staff = EdenPermissions.staff(player);
        return MailHub.LOADED ? staff : staff & ~(HomeData.STAFF_MAIL | HomeData.STAFF_MAIL_LOG);
    }

    // ---------------------------------------------------------------- opening

    public static void sendInfo(ServerPlayer player) {
        int staff = staff(player);
        if (staff == 0) {
            return;
        }
        int maxItems = MailHub.LOADED ? Economy.maxItems() : 12;
        int maxExpiry = MailHub.LOADED ? Economy.maxExpiryDays() : 30;
        List<HubPayloads.PlaceholderInfo> placeholders = new ArrayList<>();
        placeholders.add(new HubPayloads.PlaceholderInfo("{player}", player.getGameProfile().getName()));
        placeholders.add(new HubPayloads.PlaceholderInfo("{player_uuid}", player.getUUID().toString()));
        for (String[] entry : PlaceholdersCompat.all(player)) {
            placeholders.add(new HubPayloads.PlaceholderInfo(entry[0], entry[1]));
        }
        HubPayloads.NewsView news = (staff & HomeData.STAFF_NEWS) != 0
                ? net.ledok.eden_ld.hub.news.NewsBoard.get(player.server).view() : HubPayloads.NewsView.EMPTY;
        List<HubPayloads.LookRule> backgrounds = (staff & HomeData.STAFF_BACKGROUNDS) != 0
                ? net.ledok.eden_ld.hub.profile.LookRules.get(player.server, net.ledok.eden_ld.hub.profile.LookRules.Kind.BACKGROUND).all()
                : List.of();
        List<HubPayloads.LookRule> themes = (staff & HomeData.STAFF_THEMES) != 0
                ? net.ledok.eden_ld.hub.profile.LookRules.get(player.server, net.ledok.eden_ld.hub.profile.LookRules.Kind.THEME).all()
                : List.of();
        ServerPlayNetworking.send(player, new HubPayloads.ModInfo(staff, maxItems, maxExpiry,
                LuckPermsCompat.groupNames(), drafts(player), placeholders, news, backgrounds, themes));
    }

    // ---------------------------------------------------------------- drafts

    private static void saveDraft(ServerPlayer player, HubPayloads.DraftSave payload) {
        if ((staff(player) & HomeData.STAFF_MAIL) == 0) {
            return;
        }
        MinecraftServer server = player.server;
        CompoundTag root = HubData.get(server).player(player.getUUID());
        ListTag list = root.getList(DRAFTS, Tag.TAG_COMPOUND);
        long id = payload.draft().id();
        list.removeIf(tag -> ((CompoundTag) tag).getLong("id") == id);
        // An untouched draft isn't worth keeping (or listing).
        if (!payload.delete() && !isEmpty(payload.draft())) {
            list.add(0, toTag(sanitize(payload.draft(), player), server.registryAccess()));
            while (list.size() > MAX_DRAFTS) {
                list.remove(list.size() - 1);
            }
        }
        root.put(DRAFTS, list);
    }

    private static boolean isEmpty(MailDraftData d) {
        return d.name().isBlank() && d.players().isEmpty() && d.groups().isEmpty() && d.coins() == 0 && d.items().isEmpty()
                && d.texts().stream().allMatch(t -> t.title().isBlank() && t.body().isBlank());
    }

    private static List<MailDraftData> drafts(ServerPlayer player) {
        ListTag list = HubData.get(player.server).peek(player.getUUID()).getList(DRAFTS, Tag.TAG_COMPOUND);
        List<MailDraftData> drafts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            drafts.add(fromTag(list.getCompound(i), player.server.registryAccess()));
        }
        drafts.sort(Comparator.comparingLong(MailDraftData::updatedAt).reversed());
        return drafts;
    }

    /** Within the limits: text lengths, item count, expiry, no negative coins. */
    private static MailDraftData sanitize(MailDraftData d, ServerPlayer player) {
        int maxItems = MailHub.LOADED ? Economy.maxItems() : 12;
        int maxExpiry = MailHub.LOADED ? Economy.maxExpiryDays() : 30;
        List<ItemStack> items = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < d.items().size() && items.size() < maxItems; i++) {
            ItemStack stack = d.items().get(i);
            if (!stack.isEmpty()) {
                items.add(stack.copyWithCount(1));
                counts.add(Math.max(1, Math.min(99_999, i < d.counts().size() ? d.counts().get(i) : 1)));
            }
        }
        String name = d.name().strip();
        return new MailDraftData(d.id(), name.length() > 48 ? name.substring(0, 48) : name,
                Math.max(0, Math.min(2, d.audience())), d.players(), d.groups(),
                d.sender().length() > 32 ? d.sender().substring(0, 32) : d.sender(),
                Math.max(1, Math.min(maxExpiry, d.expiryDays())), Math.max(0L, d.coins()), d.defaultLanguage(),
                d.texts(), items, counts, System.currentTimeMillis());
    }

    private static CompoundTag toTag(MailDraftData d, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", d.id());
        tag.putString("name", d.name());
        tag.putInt("audience", d.audience());
        tag.put("players", strings(d.players()));
        tag.put("groups", strings(d.groups()));
        tag.putString("sender", d.sender());
        tag.putInt("expiry", d.expiryDays());
        tag.putLong("coins", d.coins());
        tag.putString("language", d.defaultLanguage());
        ListTag texts = new ListTag();
        for (HubPayloads.MailText text : d.texts()) {
            CompoundTag t = new CompoundTag();
            t.putString("language", text.language());
            t.putString("title", text.title());
            t.putString("body", text.body());
            texts.add(t);
        }
        tag.put("texts", texts);
        ListTag items = new ListTag();
        for (int i = 0; i < d.items().size(); i++) {
            CompoundTag item = new CompoundTag();
            item.put("stack", d.items().get(i).save(registries));
            item.putInt("count", d.counts().get(i));
            items.add(item);
        }
        tag.put("items", items);
        tag.putLong("updated", d.updatedAt());
        return tag;
    }

    private static MailDraftData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        List<HubPayloads.MailText> texts = new ArrayList<>();
        ListTag textList = tag.getList("texts", Tag.TAG_COMPOUND);
        for (int i = 0; i < textList.size(); i++) {
            CompoundTag t = textList.getCompound(i);
            texts.add(new HubPayloads.MailText(t.getString("language"), t.getString("title"), t.getString("body")));
        }
        List<ItemStack> items = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        ListTag itemList = tag.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag item = itemList.getCompound(i);
            Optional<ItemStack> stack = ItemStack.parse(registries, item.getCompound("stack"));
            if (stack.isPresent() && !stack.get().isEmpty()) {
                items.add(stack.get());
                counts.add(item.getInt("count"));
            }
        }
        return new MailDraftData(tag.getLong("id"), tag.getString("name"), tag.getInt("audience"), readStrings(tag.getList("players", Tag.TAG_STRING)),
                readStrings(tag.getList("groups", Tag.TAG_STRING)), tag.getString("sender"), tag.getInt("expiry"),
                tag.getLong("coins"), tag.getString("language"), texts, items, counts, tag.getLong("updated"));
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        values.forEach(v -> list.add(StringTag.valueOf(v)));
        return list;
    }

    private static List<String> readStrings(ListTag list) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    // ---------------------------------------------------------------- sending

    /** Who a draft reaches: everyone ({@code all}), or players — plus the names nobody has joined with. */
    private record Recipients(boolean all, Set<UUID> players, List<String> unknown) {
    }

    private static void send(ServerPlayer player, HubPayloads.MailSend payload) {
        if ((staff(player) & HomeData.STAFF_MAIL) == 0 || !MailHub.LOADED) {
            return;
        }
        MailDraftData draft = sanitize(payload.draft(), player);
        saveDraft(player, new HubPayloads.DraftSave(draft, false));
        List<String> problems = new ArrayList<>();
        if (draft.texts().stream().noneMatch(t -> !t.title().isBlank())) {
            problems.add("gui.eden_ld.moderation.problem.no_title");
        }
        resolve(player.server, draft).whenComplete((recipients, error) -> player.server.execute(() -> {
            if (error != null) {
                EdenLD.LOGGER.warn("[Mail] Can't resolve recipients", error);
                notice(player, Component.translatable("message.eden_ld.mail.failed"), false);
                return;
            }
            if (!recipients.all() && recipients.players().isEmpty()) {
                problems.add("gui.eden_ld.moderation.problem.no_recipients");
            }
            int count = recipients.all() ? -1 : recipients.players().size();
            if (!payload.confirm() || !problems.isEmpty()) {
                ServerPlayNetworking.send(player, new HubPayloads.MailSendCheck(draft.id(), count,
                        recipients.unknown(), problems));
                return;
            }
            Economy.send(player, draft, recipients).whenComplete((id, sendError) -> player.server.execute(() -> {
                if (sendError != null || id == null || id < 0) {
                    EdenLD.LOGGER.warn("[Mail] Sending failed", sendError);
                    notice(player, Component.translatable("message.eden_ld.mail.failed"), false);
                    return;
                }
                EdenLD.LOGGER.info("[Mail] {} sent mail #{} to {}", player.getGameProfile().getName(), id,
                        recipients.all() ? "everyone" : recipients.players().size() + " player(s)");
                saveDraft(player, new HubPayloads.DraftSave(draft, true));
                notice(player, count < 0 ? Component.translatable("message.eden_ld.moderation.sent_all")
                        : Component.translatable("message.eden_ld.moderation.sent", count), true);
                sendInfo(player);
            }));
        }));
    }

    private static CompletableFuture<Recipients> resolve(MinecraftServer server, MailDraftData draft) {
        if (draft.audience() == MailDraftData.TO_ALL) {
            return CompletableFuture.completedFuture(new Recipients(true, Set.of(), List.of()));
        }
        if (draft.audience() == MailDraftData.TO_GROUPS) {
            List<CompletableFuture<Set<UUID>>> lookups = new ArrayList<>();
            for (String group : draft.groups()) {
                lookups.add(LuckPermsCompat.directMembers(group));
            }
            return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                Set<UUID> players = new LinkedHashSet<>();
                lookups.forEach(lookup -> players.addAll(lookup.join()));
                return new Recipients(false, players, List.of());
            });
        }
        Set<UUID> players = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : draft.players()) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            Optional<GameProfile> profile = online != null ? Optional.of(online.getGameProfile())
                    : server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name);
            if (profile.isPresent()) {
                players.add(profile.get().getId());
            } else {
                unknown.add(name);
            }
        }
        return CompletableFuture.completedFuture(new Recipients(false, players, unknown));
    }

    // ---------------------------------------------------------------- log

    private static void log(ServerPlayer player, HubPayloads.MailLogRequest request) {
        int staff = staff(player);
        if ((staff & (HomeData.STAFF_MAIL | HomeData.STAFF_MAIL_LOG)) == 0 || !MailHub.LOADED) {
            return;
        }
        boolean everyone = request.everyone() && (staff & HomeData.STAFF_MAIL_LOG) != 0;
        if (request.recall() > 0) {
            Economy.recall(player, request.recall(), (staff & HomeData.STAFF_MAIL_LOG) != 0, everyone);
            return;
        }
        Economy.sendLog(player, everyone);
    }

    static void notice(ServerPlayer player, Component message, boolean ok) {
        ServerPlayNetworking.send(player, new HubPayloads.ModNotice(message, ok));
    }

    /** The only class touching Economy_LD types. */
    private static final class Economy {
        static int maxItems() {
            return net.ledok.economy_ld.mail.EconomyMail.maxItems();
        }

        static int maxExpiryDays() {
            return net.ledok.economy_ld.mail.EconomyMail.maxExpiryDays();
        }

        static CompletableFuture<Long> send(ServerPlayer admin, MailDraftData draft, Recipients recipients) {
            Map<String, net.ledok.economy_ld.mail.MailContent.Text> texts = new LinkedHashMap<>();
            for (HubPayloads.MailText text : draft.texts()) {
                if (!text.title().isBlank() || !text.body().isBlank()) {
                    texts.put(text.language(), new net.ledok.economy_ld.mail.MailContent.Text(text.title(), text.body()));
                }
            }
            String defaultLanguage = texts.containsKey(draft.defaultLanguage()) ? draft.defaultLanguage()
                    : texts.keySet().iterator().next();
            var builder = net.ledok.economy_ld.mail.MailDraft.builder(net.ledok.economy_ld.mail.MailKind.ADMIN,
                            net.ledok.economy_ld.mail.MailContent.written(defaultLanguage, texts))
                    .sender(draft.sender().isBlank() ? DEFAULT_SENDER : draft.sender(), admin.getUUID())
                    .coins(draft.coins())
                    .expiresInDays(draft.expiryDays())
                    .audienceNote(audienceNote(draft));
            for (int i = 0; i < draft.items().size(); i++) {
                builder.item(new net.ledok.economy_ld.mail.MailAttachment(draft.items().get(i), draft.counts().get(i)));
            }
            if (recipients.all()) {
                builder.toAll();
            } else {
                builder.to(new ArrayList<>(recipients.players()));
            }
            return net.ledok.economy_ld.mail.EconomyMail.send(builder.build());
        }

        private static String audienceNote(MailDraftData draft) {
            return switch (draft.audience()) {
                case MailDraftData.TO_ALL -> "all";
                case MailDraftData.TO_GROUPS -> "groups: " + String.join(", ", draft.groups());
                default -> String.join(", ", draft.players());
            };
        }

        static void sendLog(ServerPlayer player, boolean everyone) {
            net.ledok.economy_ld.mail.EconomyMail.sent(everyone ? null : player.getUUID(), LOG_SIZE)
                    .whenComplete((sent, error) -> player.server.execute(() -> {
                        if (error != null) {
                            EdenLD.LOGGER.warn("[Mail] Can't read the mail log", error);
                            return;
                        }
                        List<HubPayloads.SentMailView> views = new ArrayList<>();
                        for (net.ledok.economy_ld.mail.SentMail mail : sent) {
                            views.add(new HubPayloads.SentMailView(view(mail),
                                    mail.senderUuid() == null ? new UUID(0, 0) : mail.senderUuid(), mail.toAll(),
                                    mail.audienceNote(), mail.recipients(), mail.read(), mail.claimed()));
                        }
                        ServerPlayNetworking.send(player, new HubPayloads.MailLog(everyone, views));
                    }));
        }

        private static HubPayloads.MailView view(net.ledok.economy_ld.mail.SentMail mail) {
            List<HubPayloads.MailText> texts = new ArrayList<>();
            mail.content().texts().forEach((language, text) ->
                    texts.add(new HubPayloads.MailText(language, text.title(), text.body())));
            List<ItemStack> items = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (net.ledok.economy_ld.mail.MailAttachment item : mail.items()) {
                items.add(item.stack().copyWithCount(1));
                counts.add(item.count());
            }
            var content = mail.content();
            return new HubPayloads.MailView(mail.id(), mail.kind().ordinal(), mail.senderName(), false,
                    content.defaultLanguage() == null ? "" : content.defaultLanguage(), texts,
                    content.titleKey() == null ? "" : content.titleKey(),
                    content.bodyKey() == null ? "" : content.bodyKey(), content.args(), items, counts, mail.coins(),
                    mail.createdAt(), mail.expiresAt(), true, false);
        }

        /** Recalls a mail: the admin's own, or anyone's with the log permission. */
        static void recall(ServerPlayer player, long mailId, boolean anyones, boolean everyone) {
            net.ledok.economy_ld.mail.EconomyMail.sent(anyones ? null : player.getUUID(), 1000)
                    .thenCompose(sent -> sent.stream().anyMatch(m -> m.id() == mailId)
                            ? net.ledok.economy_ld.mail.EconomyMail.recall(mailId)
                            : CompletableFuture.completedFuture(false))
                    .whenComplete((recalled, error) -> player.server.execute(() -> {
                        boolean ok = error == null && Boolean.TRUE.equals(recalled);
                        if (ok) {
                            EdenLD.LOGGER.info("[Mail] {} recalled mail #{}", player.getGameProfile().getName(), mailId);
                        }
                        notice(player, Component.translatable(ok ? "message.eden_ld.moderation.recalled"
                                : "message.eden_ld.mail.failed"), ok);
                        sendLog(player, everyone);
                    }));
        }
    }
}
