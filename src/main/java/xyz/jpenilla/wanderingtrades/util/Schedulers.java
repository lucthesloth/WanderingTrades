package xyz.jpenilla.wanderingtrades.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class Schedulers {
    private Schedulers() {
    }

    public static void async(final Plugin plugin, final Runnable runnable) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    public static void global(final Plugin plugin, final Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    public static void region(final Plugin plugin, final Location location, final Runnable runnable) {
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> runnable.run());
    }

    public static void entity(
        final Plugin plugin,
        final Entity entity,
        final Runnable runnable,
        final @Nullable Runnable retired
    ) {
        if (plugin.getServer().isOwnedByCurrentRegion(entity)) {
            runnable.run();
            return;
        }
        entity.getScheduler().run(plugin, task -> runnable.run(), retired);
    }
}
