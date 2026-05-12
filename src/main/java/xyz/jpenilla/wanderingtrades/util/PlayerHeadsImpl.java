package xyz.jpenilla.wanderingtrades.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.jpenilla.pluginbase.legacy.TextUtil;
import xyz.jpenilla.wanderingtrades.WanderingTrades;
import xyz.jpenilla.wanderingtrades.config.PlayerHeadConfig;
import xyz.jpenilla.wanderingtrades.integration.VaultHook;

@NullMarked
final class PlayerHeadsImpl implements PlayerHeads {
    private final WanderingTrades plugin;
    private final Map<UUID, MerchantRecipe> recipes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineLastSeen = new ConcurrentHashMap<>();
    private final Set<UUID> ignoredHeadPlayers = ConcurrentHashMap.newKeySet();
    private final ProfileCompleter profileCompleter;
    private @Nullable ScheduledTask cleanupTask;

    PlayerHeadsImpl(final WanderingTrades plugin) {
        this.plugin = plugin;
        this.profileCompleter = new ProfileCompleter(plugin);
        this.plugin.getServer().getAsyncScheduler().runAtFixedRate(
            plugin,
            task -> this.profileCompleter.run(),
            0L,
            2L,
            TimeUnit.SECONDS
        );
        this.load();
        this.scheduleCleanup();
    }

    private void scheduleCleanup() {
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
        }
        this.cleanupTask = this.plugin.getServer().getAsyncScheduler().runAtFixedRate(
            this.plugin,
            task -> removeExpired(),
            60L * 10L,
            60L * 10L,
            TimeUnit.SECONDS
        );
    }

    @Override
    public List<MerchantRecipe> randomlySelectPlayerHeads() {
        final Map<UUID, MerchantRecipe> recipes = Map.copyOf(this.recipes);
        final List<UUID> uuids = new ArrayList<>(recipes.keySet());
        final int amount = this.plugin.configManager().playerHeadConfig().getRandAmount();
        Collections.shuffle(uuids);
        final List<MerchantRecipe> selectedRecipes = new ArrayList<>();
        for (final UUID uuid : uuids) {
            if (selectedRecipes.size() >= amount) {
                break;
            }
            if (this.ignoredHeadPlayers.contains(uuid)) {
                continue;
            }
            final MerchantRecipe recipe = recipes.get(uuid);
            final PlayerProfile profile = ((SkullMeta) recipe.getResult().getItemMeta()).getPlayerProfile();
            if (profile == null || !profile.hasTextures()) {
                // Profile is not yet complete
                continue;
            }
            selectedRecipes.add(recipe);
        }
        return selectedRecipes;
    }

    @Override
    public void handleLogin(final Player player) {
        if (!this.plugin.configManager().playerHeadConfig().playerHeadsFromServer()) {
            return;
        }

        this.offlineLastSeen.remove(player.getUniqueId());
        this.addHead(player);
    }

    @Override
    public void handleLogout(final Player player) {
        if (!this.plugin.configManager().playerHeadConfig().playerHeadsFromServer()) {
            return;
        }

        this.offlineLastSeen.put(player.getUniqueId(), System.currentTimeMillis());

        if (this.isIgnored(player)) {
            this.removeIgnoredHead(player.getUniqueId());
            return;
        }

        if (this.plugin.vaultHook() != null && this.plugin.configManager().playerHeadConfig().permissionWhitelist()) {
            if (!player.hasPermission(Constants.Permissions.WANDERINGTRADES_HEADAVAILABLE)) {
                this.recipes.remove(player.getUniqueId());
                this.offlineLastSeen.remove(player.getUniqueId());
            }
        }
    }

    @Override
    public void configChanged() {
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
        }
        this.load();
        this.scheduleCleanup();
    }

    private MerchantRecipe getHeadRecipe(final OfflinePlayer player, final String name) {
        final PlayerHeadConfig playerHeadConfig = this.plugin.configManager().playerHeadConfig();
        ItemBuilder<?>.MiniMessageContext headBuilder = new HeadBuilder(player)
            .stackSize(playerHeadConfig.headsPerTrade())
            .miniMessageContext()
            .lore(playerHeadConfig.lore());
        if (playerHeadConfig.name() != null) {
            headBuilder = headBuilder
                .customName(playerHeadConfig.name().replace("{PLAYER}", name));
        }
        final ItemStack head = headBuilder.exitAndBuild();

        final MerchantRecipe recipe = new MerchantRecipe(
            head,
            0,
            playerHeadConfig.maxUses(),
            playerHeadConfig.experienceReward()
        );
        recipe.addIngredient(playerHeadConfig.ingredientOne());
        if (playerHeadConfig.ingredientTwo() != null) {
            recipe.addIngredient(playerHeadConfig.ingredientTwo());
        }

        final SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            final PlayerProfile profile = meta.getPlayerProfile();
            if (profile != null && !profile.hasTextures()) {
                this.profileCompleter.submitProfile(profile, updatedProfile -> {
                    Schedulers.global(this.plugin, () -> {
                        meta.setPlayerProfile(this.filterProfileProperties(updatedProfile));
                        head.setItemMeta(meta);
                    });
                });
            } else if (profile != null && profile.hasTextures()) {
                meta.setPlayerProfile(this.filterProfileProperties(profile));
                head.setItemMeta(meta);
            }
        }

        return recipe;
    }

    private PlayerProfile filterProfileProperties(final PlayerProfile profile) {
        final PlayerProfile newProfile = (PlayerProfile) profile.clone();
        newProfile.clearProperties();
        for (final ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures")) {
                // Only copy the textures, and without the signature
                newProfile.setProperty(new ProfileProperty("textures", property.getValue()));
            }
        }
        return newProfile;
    }

    private void load() {
        this.recipes.clear();
        this.offlineLastSeen.clear();
        this.ignoredHeadPlayers.clear();
        this.profileCompleter.clearQueue();
        if (!this.plugin.configManager().playerHeadConfig().playerHeadsFromServer()) {
            return;
        }
        for (final Player onlinePlayer : this.plugin.getServer().getOnlinePlayers()) {
            this.load(onlinePlayer);
        }
        for (final OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (this.recipes.containsKey(offlinePlayer.getUniqueId())) {
                continue;
            }
            this.load(offlinePlayer);
        }
    }

    private void removeExpired() {
        if (this.plugin.configManager().playerHeadConfig().days() == -1) {
            return;
        }
        for (final UUID uuid : Set.copyOf(this.offlineLastSeen.keySet())) {
            if (!this.playedRecentlyEnough(this.offlineLastSeen.get(uuid))) {
                this.offlineLastSeen.remove(uuid);
                this.recipes.remove(uuid);
            }
        }
    }

    private void load(final OfflinePlayer player) {
        final String username = player.getName();
        if (username == null || username.isBlank()) {
            return;
        }

        if (player instanceof Player onlinePlayer && onlinePlayer.isConnected()) {
            this.addHead(onlinePlayer);
        } else {
            this.addHead(player, username);
        }
    }

    private void addHead(final OfflinePlayer offlinePlayer, final String username) {
        if (this.isUsernameBlacklisted(username)) {
            return;
        }
        final long lastSeen = offlinePlayer.getLastSeen();
        if (!this.playedRecentlyEnough(lastSeen)) {
            return;
        }
        final @Nullable VaultHook vault = this.plugin.vaultHook();
        if (vault != null && vault.permissions() != null && (this.hasIgnoredPermission() || this.plugin.configManager().playerHeadConfig().permissionWhitelist())) {
            Schedulers.async(this.plugin, () -> {
                if (this.isIgnored(vault, offlinePlayer)) {
                    this.removeIgnoredHead(offlinePlayer.getUniqueId());
                    return;
                }
                if (this.plugin.configManager().playerHeadConfig().permissionWhitelist()
                    && !vault.permissions().playerHas(null, offlinePlayer, Constants.Permissions.WANDERINGTRADES_HEADAVAILABLE)) {
                    return;
                }
                Schedulers.global(this.plugin, () -> this.addOfflineHead(offlinePlayer, username, lastSeen));
            });
        } else {
            this.addOfflineHead(offlinePlayer, username, lastSeen);
        }
    }

    private void addHead(final Player player) {
        if (this.isUsernameBlacklisted(player.getName())) {
            return;
        }
        if (this.isIgnored(player)) {
            this.removeIgnoredHead(player.getUniqueId());
            return;
        }
        if (this.plugin.configManager().playerHeadConfig().permissionWhitelist()) {
            if (player.hasPermission(Constants.Permissions.WANDERINGTRADES_HEADAVAILABLE)) {
                this.addOnlineHead(player);
            }
        } else {
            this.addOnlineHead(player);
        }
    }

    private void addOnlineHead(final Player player) {
        this.ignoredHeadPlayers.remove(player.getUniqueId());
        this.recipes.put(player.getUniqueId(), this.getHeadRecipe(player, player.getName()));
    }

    private void addOfflineHead(final OfflinePlayer offlinePlayer, final String username, final long lastSeen) {
        this.ignoredHeadPlayers.remove(offlinePlayer.getUniqueId());
        this.recipes.put(offlinePlayer.getUniqueId(), this.getHeadRecipe(offlinePlayer, username));
        this.offlineLastSeen.put(offlinePlayer.getUniqueId(), lastSeen);
    }

    private void removeIgnoredHead(final UUID uuid) {
        this.ignoredHeadPlayers.add(uuid);
        this.recipes.remove(uuid);
        this.offlineLastSeen.remove(uuid);
    }

    private boolean isIgnored(final Player player) {
        final String permission = this.plugin.config().ignoredPerm();
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private boolean isIgnored(final VaultHook vault, final OfflinePlayer player) {
        final String permission = this.plugin.config().ignoredPerm();
        return permission != null && !permission.isBlank() && vault.permissions().playerHas(null, player, permission);
    }

    private boolean hasIgnoredPermission() {
        final String permission = this.plugin.config().ignoredPerm();
        return permission != null && !permission.isBlank();
    }

    private boolean isUsernameBlacklisted(final String username) {
        if (username.startsWith("*")) {
            // Don't even try to do anything for Geyser/Bedrock users
            return true;
        }
        return TextUtil.containsCaseInsensitive(username, this.plugin.configManager().playerHeadConfig().usernameBlacklist());
    }

    private boolean playedRecentlyEnough(final long lastPlayed) {
        final PlayerHeadConfig playerHeadConfig = this.plugin.configManager().playerHeadConfig();
        if (playerHeadConfig.days() == -1) {
            return true;
        }
        final LocalDateTime logout = Instant.ofEpochMilli(lastPlayed)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();
        final LocalDateTime cutoff = LocalDateTime.now()
            .minusDays(playerHeadConfig.days());
        return logout.isAfter(cutoff);
    }
}
