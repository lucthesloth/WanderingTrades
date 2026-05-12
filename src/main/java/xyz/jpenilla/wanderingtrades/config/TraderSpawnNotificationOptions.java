package xyz.jpenilla.wanderingtrades.config;

import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record TraderSpawnNotificationOptions(
    boolean enabled,
    Players notifyPlayers,
    List<String> commands,
    List<String> perPlayerCommands
) {
    private static final String ENABLED = "enabled";
    private static final String NOTIFY_PLAYERS = "notifyPlayers";
    private static final String COMMANDS = "commands";
    private static final String PER_PLAYER_COMMANDS = "perPlayerCommands";

    void setTo(final DefaultedConfig config, final String path) {
        config.set(path + "." + ENABLED, this.enabled);
        config.set(path + "." + NOTIFY_PLAYERS, this.notifyPlayers.input());
        config.set(path + "." + COMMANDS, this.commands);
        config.set(path + "." + PER_PLAYER_COMMANDS, this.perPlayerCommands);
    }

    static TraderSpawnNotificationOptions createFrom(final @Nullable ConfigurationSection section) {
        Objects.requireNonNull(section, "section");
        return new TraderSpawnNotificationOptions(
            section.getBoolean(ENABLED),
            Players.parse(section.getString(NOTIFY_PLAYERS)),
            section.getStringList(COMMANDS),
            section.getStringList(PER_PLAYER_COMMANDS)
        );
    }

    public interface Players {
        String input();

        boolean includes(String worldName, Location location, Player player);

        private static Players withInput(final String input, final PlayerFilter filter) {
            return new Players() {
                @Override
                public String input() {
                    return input;
                }

                @Override
                public boolean includes(final String worldName, final Location location, final Player player) {
                    return filter.includes(worldName, location, player);
                }
            };
        }

        static Players parse(final @Nullable String value) {
            Objects.requireNonNull(value, "value");
            if (value.equalsIgnoreCase("all")) {
                return withInput(value, (worldName, location, player) -> true);
            } else if (value.equalsIgnoreCase("world")) {
                return withInput(value, (worldName, location, player) -> player.getWorld().getName().equals(worldName));
            }
            final boolean box = value.endsWith("box");
            try {
                final int radius = Integer.parseInt(box ? value.substring(0, value.length() - 3) : value);
                return withInput(value, (worldName, location, player) -> {
                    if (!player.getWorld().getName().equals(worldName)) {
                        return false;
                    }
                    final Location playerLocation = player.getLocation();
                    if (Math.abs(playerLocation.getX() - location.getX()) > radius
                        || Math.abs(playerLocation.getZ() - location.getZ()) > radius) {
                        return false;
                    }
                    return !box || Math.abs(playerLocation.getY() - location.getY()) <= radius;
                });
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid players option, got '" + value + "', expected 'all', 'world', or an integer number for radius.");
            }
        }
    }

    @FunctionalInterface
    private interface PlayerFilter {
        boolean includes(String worldName, Location location, Player player);
    }
}
