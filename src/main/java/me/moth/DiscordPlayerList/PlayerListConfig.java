package me.moth.DiscordPlayerList;

import dcshadow.com.moandjiezana.toml.TomlComment;

public class PlayerListConfig {
	@TomlComment({"The ID of the guild where the channels will be created"})
	public String guildID = "";

	@TomlComment({"The ID of category where the channels will be created"})
	public String categoryID = "";

	@TomlComment({"Interval of updates in seconds"})
	public int updateInterval = 1;
}
