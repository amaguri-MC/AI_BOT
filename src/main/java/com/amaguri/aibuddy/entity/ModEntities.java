package com.amaguri.aibuddy.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import com.amaguri.aibuddy.AiBuddyMod;

public final class ModEntities {
	public static EntityType<FakeMember> FAKE_MEMBER;

	private ModEntities() {
	}

	public static void init() {
		Identifier id = AiBuddyMod.id("fake_member");
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
		// MISC: not part of natural spawning. Player-sized hitbox (0.6 x 1.8, eye 1.62).
		FAKE_MEMBER = Registry.register(BuiltInRegistries.ENTITY_TYPE, id,
				EntityType.Builder.<FakeMember>of(FakeMember::new, MobCategory.MISC)
						.sized(0.6f, 1.8f)
						.eyeHeight(1.62f)
						.clientTrackingRange(10)
						.build(key));
		FabricDefaultAttributeRegistry.register(FAKE_MEMBER, FakeMember.createAttributes());
	}
}
