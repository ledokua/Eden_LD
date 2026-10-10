package net.ledok.eden_ld.command;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /eden commands dump}: every command on the server, by the mod that registered it, with
 * the op level a player needs for it — the root and every subcommand that needs a different level
 * than its parent. Written to {@code eden_ld-commands.md} in the server folder, for deciding which
 * commands players get.
 * <p>
 * Levels are checked with a console source at op levels 0–4, so a mod's permission node counts as
 * unset (its op fallback decides). A command no level passes from the console is for players only
 * or needs a node; it's listed as "—".
 */
public final class CommandDump {

    public static final String FILE = "eden_ld-commands.md";
    /** A level no console source reaches. */
    private static final int NEVER = 5;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_LINES_PER_COMMAND = 60;

    private record Line(String path, int level, String note) {
    }

    private CommandDump() {
    }

    /** Writes the file; returns {commands, mods}. */
    public static int[] write(MinecraftServer server) throws IOException {
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        CommandSourceStack[] sources = new CommandSourceStack[NEVER];
        for (int level = 0; level < NEVER; level++) {
            sources[level] = server.createCommandSourceStack().withPermission(level);
        }
        Map<String, List<List<Line>>> byMod = new TreeMap<>();
        Map<String, String> ownerCache = new HashMap<>();
        List<String> open = new ArrayList<>();
        List<CommandNode<CommandSourceStack>> roots = new ArrayList<>(root.getChildren());
        roots.sort(Comparator.comparing(CommandNode::getName));
        for (CommandNode<CommandSourceStack> node : roots) {
            List<Line> lines = new ArrayList<>();
            int level = level(node, 0, sources);
            String note = node.getRedirect() != null && node.getRedirect() != root
                    ? "alias of `/" + node.getRedirect().getName() + "`" : "";
            lines.add(new Line("/" + node.getName(), level, note));
            if (node.getRedirect() == null) {
                walk(node, "/" + node.getName(), level, 1, sources, lines);
            }
            if (level == 0) {
                open.add("/" + node.getName());
            }
            byMod.computeIfAbsent(owner(node, ownerCache), mod -> new ArrayList<>()).add(lines);
        }

        StringBuilder out = new StringBuilder();
        out.append("# Commands on this server\n\n")
                .append("Written by `/eden commands dump` on ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(". Level: **0** = every player, **2** = ops, **4** = server owner, **—** = never from the ")
                .append("console (players only, or needs a permission node). Subcommands are listed when they need ")
                .append("a different level than their parent.\n\n")
                .append("**Open to every player (").append(open.size()).append("):** ");
        for (int i = 0; i < open.size(); i++) {
            out.append(i > 0 ? ", " : "").append('`').append(open.get(i)).append('`');
        }
        out.append("\n");
        for (Map.Entry<String, List<List<Line>>> mod : byMod.entrySet()) {
            String name = FabricLoader.getInstance().getModContainer(mod.getKey())
                    .map(container -> container.getMetadata().getName()).orElse(mod.getKey());
            out.append("\n## ").append(name).append(" (`").append(mod.getKey()).append("`)\n\n")
                    .append("| Command | Level | Note |\n| --- | --- | --- |\n");
            for (List<Line> lines : mod.getValue()) {
                for (Line line : lines) {
                    out.append("| `").append(line.path()).append("` | ")
                            .append(line.level() >= NEVER ? "—" : String.valueOf(line.level()))
                            .append(" | ").append(line.note()).append(" |\n");
                }
            }
        }
        Path file = server.getServerDirectory().resolve(FILE);
        Files.writeString(file, out.toString());
        return new int[]{roots.size(), byMod.size()};
    }

    /** Subcommands whose level differs from their parent's (arguments shown as {@code <name>}). */
    private static void walk(CommandNode<CommandSourceStack> node, String path, int parentLevel, int depth,
                             CommandSourceStack[] sources, List<Line> lines) {
        if (depth > MAX_DEPTH) {
            return;
        }
        List<CommandNode<CommandSourceStack>> children = new ArrayList<>(node.getChildren());
        children.sort(Comparator.comparing(CommandNode::getName));
        for (CommandNode<CommandSourceStack> child : children) {
            if (lines.size() >= MAX_LINES_PER_COMMAND) {
                return;
            }
            String childPath = path + " " + (child instanceof ArgumentCommandNode<?, ?> ? "<" + child.getName() + ">" : child.getName());
            int level = level(child, parentLevel, sources);
            if (level != parentLevel) {
                lines.add(new Line(childPath, level, ""));
            }
            if (child.getRedirect() == null) {
                walk(child, childPath, level, depth + 1, sources, lines);
            }
        }
    }

    /** The lowest op level passing this node's requirement (never below its parent's). */
    private static int level(CommandNode<CommandSourceStack> node, int parentLevel, CommandSourceStack[] sources) {
        for (int level = parentLevel; level < NEVER; level++) {
            try {
                if (node.canUse(sources[level])) {
                    return level;
                }
            } catch (RuntimeException e) {
                // A requirement that can't handle the console: players only.
                return NEVER;
            }
        }
        return NEVER;
    }

    /**
     * The mod whose code runs the command (else whose code checks it): the mod whose files hold
     * that class (its jar, or its classes folder in a dev run). An alias belongs to its target.
     */
    private static String owner(CommandNode<CommandSourceStack> node, Map<String, String> cache) {
        if (node.getRedirect() != null && !node.getRedirect().getName().isEmpty()) {
            return owner(node.getRedirect(), cache);
        }
        Object code = firstCommand(node, 0);
        if (code == null) {
            code = node.getRequirement();
        }
        Class<?> type = code.getClass();
        String name = type.getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda > 0) {
            name = name.substring(0, lambda);
        }
        int inner = name.indexOf('$');
        String outer = inner > 0 ? name.substring(0, inner) : name;
        return cache.computeIfAbsent(outer, cls -> {
            // A lambda shares its host class's code source.
            Path source = codeSource(type);
            String file = cls.replace('.', '/') + ".class";
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                try {
                    if (source != null) {
                        for (Path path : mod.getOrigin().getPaths()) {
                            if (source.equals(path.toAbsolutePath().normalize())) {
                                return mod.getMetadata().getId();
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    // Nested or unusual origin: try the file lookup below.
                }
            }
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                try {
                    if (mod.findPath(file).isPresent()) {
                        return mod.getMetadata().getId();
                    }
                } catch (RuntimeException e) {
                    EdenLD.LOGGER.debug("[Commands] Can't look into {}: {}", mod.getMetadata().getId(), e.toString());
                }
            }
            // Last resort (a dev run keeps classes outside the mod's folder): a mod id in the package.
            java.util.Set<String> segments = new java.util.HashSet<>(java.util.Arrays.asList(cls.split("\\.")));
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                if (segments.contains(mod.getMetadata().getId())) {
                    return mod.getMetadata().getId();
                }
            }
            return "unknown (" + cls + ")";
        });
    }

    private static Path codeSource(Class<?> type) {
        try {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(location.toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static Object firstCommand(CommandNode<CommandSourceStack> node, int depth) {
        if (node.getCommand() != null) {
            return node.getCommand();
        }
        if (depth > MAX_DEPTH || node.getRedirect() != null) {
            return null;
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            Object found = firstCommand(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
