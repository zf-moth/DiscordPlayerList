package me.moth.DiscordPlayerList;

import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.addon.AddonConfigRegistry;
import de.erdbeerbaerlp.dcintegration.common.addon.DiscordIntegrationAddon;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.PlayerLink;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.Channel;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Addon to create channels under a category with the name of the players on the server
 */
public class PlayerListAddon implements DiscordIntegrationAddon {
	private PlayerListConfig cfg;
	DiscordIntegration discord;
	Guild guild;
	// Map of player UUID to their name
	private Map<UUID, String> oldPlayerList = new HashMap<>();
	private Map<UUID, String> currentPlayerList = new HashMap<>();
	// Map of player UUID to the channel ID
	private Map<UUID, String> createdChannels = new HashMap<>();
	// Scheduler to check player list every second
	private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


	@Override
	public void load(DiscordIntegration dc) {
		try {
			DiscordIntegration.LOGGER.info("Loading PlayerList Addon");
			cfg = AddonConfigRegistry.loadConfig(PlayerListConfig.class, this);
			discord = dc;
			guild = discord.getJDA().getGuildById(cfg.guildID);
			Thread.sleep(10000); // Wait for the server to load
			if (checkIfGuildExists()) {
				DiscordIntegration.LOGGER.error("Guild with ID " + cfg.guildID + " does not exist!");
				return;
			}
			if (checkIfCategoryExists()) {
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
			guild = discord.getJDA().getGuildById(cfg.guildID);
			scheduler.shutdown();
			if (checkIfGuildExists()) {
				DiscordIntegration.LOGGER.error("Guild with ID " + cfg.guildID + " does not exist!");
				return;
			}
			if (checkIfCategoryExists()) {
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
			removeChannels();
			scheduler.shutdown();
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error during unload: ", e);
		}
	}

	/**
	 * Start a scheduler to check player list every second
	 */
	private void startPlayerListCheck() {
		try {
			scheduler.scheduleAtFixedRate(() -> {
				try {
					checkAndUpdatePlayerList();
				} catch (Exception e) {
					DiscordIntegration.LOGGER.error("Error checking and updating player list: ", e);
				}
			}, 0, cfg.updateInterval, TimeUnit.SECONDS);
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error starting player list check: ", e);
		}
	}

	/**
	 * Check and update the player list
	 */
	private void checkAndUpdatePlayerList() {
		try {
			currentPlayerList = getCurrentPlayerList();
			if (!currentPlayerList.equals(oldPlayerList)) {
				updateChannels();
				oldPlayerList = currentPlayerList;
			}
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error checking and updating player list: ", e);
		}
	}

	/**
	 * Update the channels based on the player list
	 */
	private void updateChannels() {
		try {
			// Update the category name to onlinePlayer/totalPlayerSize
			// TODO: Renaming is rate limited to 2 per 10 minutes, find a better way to show player count
			// guild.getCategoryById(cfg.categoryID)
			//       .getManager().setName("Online: " + currentPlayerList.size() + "/" + discord.getServerInterface().getMaxPlayers()).queue();
			// Remove channels for players who left
			oldPlayerList.keySet().stream()
					.filter(uuid -> !currentPlayerList.containsKey(uuid))
					.forEach(uuid -> {
						String channelId = createdChannels.remove(uuid);
						if (channelId != null) {
							guild.getTextChannelById(channelId).delete().queue();
						}
					});
			// Add channels for new players
			currentPlayerList.keySet().stream()
					.filter(uuid -> !oldPlayerList.containsKey(uuid))
					.forEach(uuid -> {
						String channelName = getDiscordName(uuid);
						if (channelName == null) {
							channelName = currentPlayerList.get(uuid);
						}
						guild.getCategoryById(cfg.categoryID)
								.createTextChannel(channelName)
								.addPermissionOverride(
										guild.getPublicRole(), EnumSet.of(Permission.VIEW_CHANNEL), EnumSet.of(Permission.MESSAGE_SEND)
								)
								.queue(channel -> createdChannels.put(uuid, channel.getId()));
					});
			Thread.sleep(1000); // Wait for the channels to be created
			sortChannels();
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error updating channels: ", e);
		}
	}

	/**
	 * Check if the guild exists
	 *
	 * @return true if the guild does not exist, false otherwise
	 */
	private boolean checkIfGuildExists() {
		try {
			return guild == null;
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error checking if guild exists: ", e);
			return true;
		}
	}

	/**
	 * Check if the player list category exists
	 *
	 * @return true if the category does not exist, false otherwise
	 */
	private boolean checkIfCategoryExists() {
		try {
			return guild.getCategoryById(cfg.categoryID) == null;
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error checking if player category exists: ", e);
			return true;
		}
	}

	/**
	 * Remove all channels under the category
	 */
	private void removeChannels() {
		try {
			guild.getCategoryById(cfg.categoryID)
					.getChannels().forEach(channel -> channel.delete().queue());
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error removing channels: ", e);
		}
	}

	/**
	 * Get the current player list
	 *
	 * @return Map of player UUID to their name
	 */
	private Map<UUID, String> getCurrentPlayerList() {
		try {
			return discord.getServerInterface().getPlayers();
		} catch (Exception e) {
			DiscordIntegration.LOGGER.error("Error getting current player list: ", e);
			return new HashMap<>();
		}
	}

	/**
	 * Get the discord name of a player
	 *
	 * @param p Player UUID
	 * @return Discord name of the player, or null if the player is not linked or the player does not have a discord name
	 */
	private String getDiscordName(final UUID p) {
		if (discord == null) return null;
		if (Configuration.instance().linking.enableLinking && LinkManager.isPlayerLinked(p)) {
			final PlayerLink link = LinkManager.getLink(null, p);
			if (link.settings.useDiscordNameInChannel) {
				AtomicReference<String> discordName = new AtomicReference<>();
				guild.retrieveMemberById(link.discordID).queue(member -> {
							discordName.set(member.getEffectiveName());
						});
				return discordName.get();
			}
		}
		return null;
	}

	/**
	 * Sort the channels alphabetically by player name
	 */
	private void sortChannels () {
		// Sort channels alphabetically by player name
		guild.getCategoryById(cfg.categoryID)
				.modifyTextChannelPositions()
				.sortOrder(Comparator.comparing(Channel::getName))
				.queue();
	}
}
