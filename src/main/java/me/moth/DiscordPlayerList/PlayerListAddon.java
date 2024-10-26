package me.moth.DiscordPlayerList;

import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.addon.AddonConfigRegistry;
import de.erdbeerbaerlp.dcintegration.common.addon.DiscordIntegrationAddon;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.PlayerLink;
import net.dv8tion.jda.api.Permission;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// This addon will create channels under specified category, with the name of the players on the server
// Whenever a player joins or leaves the server, the channel will be updated
// We don't know when player joins or leaves the server, but we can get the list of players on the server right now
public class PlayerListAddon implements DiscordIntegrationAddon {
    private PlayerListConfig cfg;
    DiscordIntegration discord;
    private Map<UUID, String> oldPlayerList = new HashMap<>();
    private Map<UUID, String> currentPlayerList = new HashMap<>();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Override
    public void load(DiscordIntegration dc) {
        try {
            cfg = AddonConfigRegistry.loadConfig(PlayerListConfig.class, this);
            discord = dc;
            DiscordIntegration.LOGGER.info("PlayerList Addon loaded");
            if (checkIfGuildExists()) {
                DiscordIntegration.LOGGER.error("Guild with ID " + cfg.guildID + " does not exist!");
                return;
            }
            if (checkIfPlayerCategoryExists()) {
                DiscordIntegration.LOGGER.error("Category with ID " + cfg.categoryID + " does not exist!");
                return;
            }
            removeChannels();
            startPlayerListCheck();
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error during load: ", e);
        }
    }

    @Override
    public void reload() {
        try {
            DiscordIntegration.LOGGER.info("Reloading PlayerList Addon");
            cfg = AddonConfigRegistry.loadConfig(PlayerListConfig.class, this);
            scheduler.shutdown();
            if (checkIfGuildExists()) {
                DiscordIntegration.LOGGER.error("Guild with ID " + cfg.guildID + " does not exist!");
                return;
            }
            if (!checkIfPlayerCategoryExists()) {
                DiscordIntegration.LOGGER.error("Category with ID " + cfg.categoryID + " does not exist!");
                return;
            }
            removeChannels();
            oldPlayerList = new HashMap<>();
            // Reinitialize the scheduler
            scheduler = Executors.newScheduledThreadPool(1);
            startPlayerListCheck();
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error during reload: ", e);
        }
    }

    @Override
    public void unload(DiscordIntegration dc) {
        try {
            DiscordIntegration.LOGGER.info("Unloading PlayerList Addon");
            scheduler.shutdown();
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error during unload: ", e);
        }
    }

    private void startPlayerListCheck() {
        try {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    checkAndUpdatePlayerList();
                } catch (Exception e) {
                    DiscordIntegration.LOGGER.error("Error checking and updating player list: ", e);
                }
            }, 0, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error starting player list check: ", e);
        }
    }

    private void checkAndUpdatePlayerList() {
        try {
            currentPlayerList = getCurrentPlayerList();
            if (!currentPlayerList.equals(oldPlayerList)) {
                DiscordIntegration.LOGGER.info("Player list changed, updating channels");
                updateChannels();
                oldPlayerList = currentPlayerList;
            } else {
                DiscordIntegration.LOGGER.info("Player list did not change");
            }
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error checking and updating player list: ", e);
        }
    }

    private void updateChannels() {
        try {
            // Update the category name to onlinePlayer/totalPlayerSize
            // TODO: Renaming is rate limited to 2 per 10 minutes, find a better way to show player count
            //discord.getJDA().getGuildById(cfg.guildID).getCategoryById(cfg.categoryID)
            //      .getManager().setName("Online: " + currentPlayerList.size() + "/" + discord.getServerInterface().getMaxPlayers()).queue();
            // Remove channels for players who left
            oldPlayerList.keySet().stream()
                    .filter(uuid -> !currentPlayerList.containsKey(uuid))
                    .forEach(uuid -> {
                        DiscordIntegration.LOGGER.info("Removing channel for " + uuid);
                        String discordName = getDiscordName(uuid);
                        DiscordIntegration.LOGGER.info("Discord name: " + discordName);
                        String channelName = (discordName != null) ? discordName : oldPlayerList.get(uuid);
                        DiscordIntegration.LOGGER.info("Channel name: " + channelName);
                        discord.getJDA().getGuildById(cfg.guildID).getCategoryById(cfg.categoryID)
                                .getChannels().stream()
                                .filter(channel -> channel.getName().equals(channelName))
                                .forEach(channel -> channel.delete().queue());
                    });

            // Add channels for new players
            currentPlayerList.keySet().stream()
                    .filter(uuid -> !oldPlayerList.containsKey(uuid))
                    .forEach(uuid -> {
                        DiscordIntegration.LOGGER.info("Adding channel for " + uuid);
                        String discordName = getDiscordName(uuid);
                        DiscordIntegration.LOGGER.info("Discord name: " + discordName);
                        String channelName = (discordName != null) ? discordName : currentPlayerList.get(uuid);
                        DiscordIntegration.LOGGER.info("Channel name: " + channelName);
                        discord.getJDA().getGuildById(cfg.guildID).getCategoryById(cfg.categoryID)
                                .createTextChannel(channelName)
                                .addPermissionOverride(
                                        discord.getJDA().getGuildById(cfg.guildID).getPublicRole(), EnumSet.of(Permission.VIEW_CHANNEL), EnumSet.of(Permission.MESSAGE_SEND))
                                .queue();
                    });
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error updating channels: ", e);
        }
    }


    public boolean checkIfGuildExists() {
        try {
            return discord.getJDA().getGuildById(cfg.guildID) == null;
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error checking if discord.getJDA().getGuildById(cfg.guildID) exists: ", e);
            return true;
        }
    }

    public boolean checkIfPlayerCategoryExists() {
        try {
            return discord.getJDA().getGuildById(cfg.guildID).getCategoryById(cfg.categoryID) != null;
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error checking if player category exists: ", e);
            return true;
        }
    }

    public void removeChannels() {
        try {
            // Remove all channels under the category
            discord.getJDA().getGuildById(cfg.guildID).getCategoryById(cfg.categoryID)
                    .getChannels().forEach(channel -> channel.delete().queue());
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error removing channels: ", e);
        }
    }

    public Map<UUID, String> getCurrentPlayerList() {
        try {
            return discord.getServerInterface().getPlayers();
        } catch (Exception e) {
            DiscordIntegration.LOGGER.error("Error getting current player list: ", e);
            return new HashMap<>();
        }
    }

    public static String getDiscordName(final UUID p) {
        // Log the player's UUID and Discord name
        DiscordIntegration.LOGGER.info("Getting Discord name for player: " + p);
        if (DiscordIntegration.INSTANCE == null) return null;
        if (Configuration.instance().linking.enableLinking && LinkManager.isPlayerLinked(p)) {
            final PlayerLink link = LinkManager.getLink(null, p);
            if (link.settings.useDiscordNameInChannel) {
                String discordName = DiscordIntegration.INSTANCE.getChannel().getGuild().getMemberById(link.discordID).getEffectiveName();
                DiscordIntegration.LOGGER.info("Discord name: " + discordName);
                return discordName;
            }
        }
        return null;
    }

}
