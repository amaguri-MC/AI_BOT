package com.amaguri.aibuddy;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaguri.aibuddy.brain.BrainManager;
import com.amaguri.aibuddy.command.AiBuddyCommands;
import com.amaguri.aibuddy.entity.ModEntities;
import com.amaguri.aibuddy.item.ModItems;
import com.amaguri.aibuddy.loot.ModLoot;

public class AiBuddyMod implements ModInitializer {
	public static final String MOD_ID = "aibuddy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModItems.init();
		ModLoot.init();
		ModEntities.init();
		AiBuddyCommands.init();
		BrainManager.init();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
