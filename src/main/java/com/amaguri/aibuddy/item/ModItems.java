package com.amaguri.aibuddy.item;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;

import com.amaguri.aibuddy.AiBuddyMod;

public final class ModItems {
	public static Item TAKO_ASHI;
	public static Item TAKOYAKI;

	private ModItems() {
	}

	public static void init() {
		// UNVERIFIED: Item.Properties#food(FoodProperties) and FoodProperties.Builder (nutrition/saturationModifier) are
		// recalled from 1.21.x; check against 26.2. Item.Properties#setId(ResourceKey) is confirmed in Fabric testmods.
		TAKO_ASHI = register("tako_ashi", new Item.Properties()
				.food(new FoodProperties.Builder().nutrition(2).saturationModifier(0.1f).build()));
		TAKOYAKI = register("takoyaki", new Item.Properties()
				.food(new FoodProperties.Builder().nutrition(6).saturationModifier(0.6f).build()));
	}

	private static Item register(String name, Item.Properties properties) {
		Identifier id = AiBuddyMod.id(name);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
		return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties.setId(key)));
	}
}
