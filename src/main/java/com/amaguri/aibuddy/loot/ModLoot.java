package com.amaguri.aibuddy.loot;

import java.util.List;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

import com.amaguri.aibuddy.item.ModItems;

/** イカ（発光イカ含む）を倒したときだけ 10% でタコ足を落とす。 */
public final class ModLoot {
	private static final float SQUID_CHANCE = 0.10f;

	private ModLoot() {
	}

	public static void init() {
		LootTableEvents.MODIFY_DROPS.register(ModLoot::modifyDrops);
	}

	private static void modifyDrops(Holder<LootTable> holder, LootContext context, List<ItemStack> drops) {
		String path = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("");
		if ((path.equals("minecraft:entities/squid") || path.equals("minecraft:entities/glow_squid"))
				&& context.getRandom().nextFloat() < SQUID_CHANCE) {
			drops.add(new ItemStack(ModItems.TAKO_ASHI));
		}
	}
}
