package xyz.jpenilla.wanderingtrades.listener;

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
                this.notifyPlayers(trader);
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
}
