package xyz.jpenilla.wanderingtrades.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.jpenilla.wanderingtrades.WanderingTrades;
import xyz.jpenilla.wanderingtrades.config.TraderSpawnNotificationOptions;
import xyz.jpenilla.wanderingtrades.util.Constants;
import xyz.jpenilla.wanderingtrades.util.Schedulers;

@NullMarked
public final class TraderSpawnListener implements Listener {
    private final WanderingTrades plugin;

    public TraderSpawnListener(final WanderingTrades plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPortal(final EntityPortalEvent event) {
        if (event.getEntityType() == EntityType.WANDERING_TRADER) {
            event.getEntity().getPersistentDataContainer().set(Constants.TEMPORARY_BLACKLISTED, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof final WanderingTrader trader) || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.MOUNT) {
            return;
        }
        if (trader.getPersistentDataContainer().has(Constants.TEMPORARY_BLACKLISTED, PersistentDataType.BYTE)) {
            trader.getPersistentDataContainer().remove(Constants.TEMPORARY_BLACKLISTED);
            return;
        }
        // Delay by 1 tick so entity is in world
        trader.getScheduler().runDelayed(this.plugin, task -> {
            if (trader.isValid()) {
                this.relocateIfIgnoredOrigin(trader).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getLogger().log(Level.WARNING, "Failed to relocate wandering trader away from ignored player", throwable);
                    }
                    Schedulers.entity(this.plugin, trader, () -> {
                        if (trader.isValid()) {
                            this.notifyPlayers(trader);
                        }
                    }, null);
                });
            }
        }, null, 1L);

        if (this.plugin.config().traderWorldWhitelist()) {
            if (this.plugin.config().traderWorldList().contains(event.getEntity().getWorld().getName())) {
                this.plugin.tradeApplicator().addTrades(trader);
            }
        } else {
            if (!this.plugin.config().traderWorldList().contains(event.getEntity().getWorld().getName())) {
                this.plugin.tradeApplicator().addTrades(trader);
            }
        }
    }

    private void notifyPlayers(final WanderingTrader entity) {
        final TraderSpawnNotificationOptions options = this.plugin.config().traderSpawnNotificationOptions();
        if (!options.enabled()) {
            return;
        }
        final TraderSpawn spawn = TraderSpawn.from(entity);
        for (final String command : options.commands()) {
            this.dispatchCommand(applyNotifyCommandReplacements(spawn, null, command));
        }
        Schedulers.global(this.plugin, () -> {
            for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
                Schedulers.entity(this.plugin, player, () -> this.notifyPlayer(options, spawn, player), null);
            }
        });
    }

    private void notifyPlayer(final TraderSpawnNotificationOptions options, final TraderSpawn spawn, final Player player) {
        if (this.isIgnored(player)) {
            return;
        }
        if (!player.hasPermission(Constants.Permissions.TRADER_SPAWN_NOTIFICATIONS)) {
            return;
        }
        if (!options.notifyPlayers().includes(spawn.worldName(), spawn.location(), player)) {
            return;
        }
        for (final String command : options.perPlayerCommands()) {
            this.dispatchCommand(applyNotifyCommandReplacements(spawn, player, command));
        }
    }

    private CompletableFuture<Void> relocateIfIgnoredOrigin(final WanderingTrader trader) {
        final Location spawnLocation = trader.getLocation().clone();
        final int range = this.plugin.config().traderSpawnNotificationOptions().notifyPlayers().range();
        return this.gatherOnlinePlayerSnapshots().thenCompose(players -> {
            final @Nullable PlayerSnapshot closest = closestPlayer(players, spawnLocation);
            if (closest == null || !closest.ignored()) {
                return CompletableFuture.completedFuture(null);
            }

            final List<PlayerSnapshot> candidates = new ArrayList<>();
            for (final PlayerSnapshot player : players) {
                if (!player.ignored()) {
                    candidates.add(player);
                }
            }
            if (candidates.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            final PlayerSnapshot target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            return this.relocationTarget(target, range).thenCompose(location -> this.teleportTrader(trader, location));
        });
    }

    private CompletableFuture<List<PlayerSnapshot>> gatherOnlinePlayerSnapshots() {
        final CompletableFuture<List<PlayerSnapshot>> result = new CompletableFuture<>();
        Schedulers.global(this.plugin, () -> {
            final List<CompletableFuture<@Nullable PlayerSnapshot>> snapshotFutures = new ArrayList<>();
            for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
                final CompletableFuture<@Nullable PlayerSnapshot> snapshotFuture = new CompletableFuture<>();
                snapshotFutures.add(snapshotFuture);
                Schedulers.entity(
                    this.plugin,
                    player,
                    () -> snapshotFuture.complete(PlayerSnapshot.from(player, this.isIgnored(player))),
                    () -> snapshotFuture.complete(null)
                );
            }
            if (snapshotFutures.isEmpty()) {
                result.complete(List.of());
                return;
            }

            final CompletableFuture<?>[] futures = snapshotFutures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futures).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    result.completeExceptionally(throwable);
                    return;
                }

                final List<PlayerSnapshot> players = new ArrayList<>();
                for (final CompletableFuture<@Nullable PlayerSnapshot> snapshotFuture : snapshotFutures) {
                    final @Nullable PlayerSnapshot snapshot = snapshotFuture.join();
                    if (snapshot != null) {
                        players.add(snapshot);
                    }
                }
                result.complete(players);
            });
        });
        return result;
    }

    private CompletableFuture<Location> relocationTarget(final PlayerSnapshot target, final int range) {
        final int x = target.blockX() + randomOffset(range);
        final int z = target.blockZ() + randomOffset(range);
        final Location regionLocation = new Location(target.world(), x, target.y(), z);
        final CompletableFuture<Location> result = new CompletableFuture<>();
        Schedulers.region(this.plugin, regionLocation, () -> {
            final int y = target.world().getHighestBlockYAt(x, z) + 1;
            result.complete(new Location(target.world(), x + 0.5D, y, z + 0.5D, target.yaw(), target.pitch()));
        });
        return result;
    }

    private CompletableFuture<Void> teleportTrader(final WanderingTrader trader, final Location location) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        Schedulers.entity(this.plugin, trader, () -> {
            if (!trader.isValid()) {
                result.complete(null);
                return;
            }
            trader.teleportAsync(location).whenComplete((success, throwable) -> {
                if (throwable != null) {
                    result.completeExceptionally(throwable);
                    return;
                }
                result.complete(null);
            });
        }, () -> result.complete(null));
        return result;
    }

    private static @Nullable PlayerSnapshot closestPlayer(final List<PlayerSnapshot> players, final Location location) {
        @Nullable PlayerSnapshot closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (final PlayerSnapshot player : players) {
            final double distance = player.distanceSquared(location);
            if (distance < closestDistance) {
                closest = player;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static int randomOffset(final int range) {
        if (range <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextInt(-range, range + 1);
    }

    private boolean isIgnored(final Player player) {
        final String permission = this.plugin.config().ignoredPerm();
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private void dispatchCommand(final String command) {
        Schedulers.global(this.plugin, () -> this.plugin.getServer().dispatchCommand(
            this.plugin.getServer().getConsoleSender(),
            command
        ));
    }

    private static String applyNotifyCommandReplacements(final TraderSpawn spawn, final @Nullable Player player, String command) {
        if (player != null) {
            command = command.replace("{player}", player.getName());
            if (player.getWorld().getName().equals(spawn.worldName())) {
                command = command.replace("{distance}", String.valueOf(Math.round(player.getLocation().distance(spawn.location()))));
            }
        }
        return command.replace("{world-name}", spawn.worldName())
            .replace("{x-pos}", String.valueOf(spawn.x()))
            .replace("{y-pos}", String.valueOf(spawn.y()))
            .replace("{z-pos}", String.valueOf(spawn.z()))
            .replace("{trader-uuid}", spawn.uuid());
    }

    private record TraderSpawn(String worldName, int x, int y, int z, String uuid, org.bukkit.Location location) {
        private static TraderSpawn from(final WanderingTrader trader) {
            return new TraderSpawn(
                trader.getWorld().getName(),
                trader.getLocation().getBlockX(),
                trader.getLocation().getBlockY(),
                trader.getLocation().getBlockZ(),
                trader.getUniqueId().toString(),
                trader.getLocation().clone()
            );
        }
    }

    private record PlayerSnapshot(
        World world,
        String worldName,
        int blockX,
        int blockZ,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean ignored
    ) {
        private static PlayerSnapshot from(final Player player, final boolean ignored) {
            final Location location = player.getLocation();
            return new PlayerSnapshot(
                player.getWorld(),
                player.getWorld().getName(),
                location.getBlockX(),
                location.getBlockZ(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                ignored
            );
        }

        private double distanceSquared(final Location location) {
            if (!this.worldName.equals(location.getWorld().getName())) {
                return Double.MAX_VALUE;
            }
            final double deltaX = this.x - location.getX();
            final double deltaY = this.y - location.getY();
            final double deltaZ = this.z - location.getZ();
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }
}
