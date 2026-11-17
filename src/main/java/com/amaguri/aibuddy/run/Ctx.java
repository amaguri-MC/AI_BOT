package com.amaguri.aibuddy.run;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.skill.SkillRunner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** 判断表が読む「持ち物と周囲」。1回の判断ごとに作る。 */
final class Ctx {
	static final Item[] COOKED = { Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN,
			Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BREAD, Items.BAKED_POTATO, Items.GOLDEN_CARROT };
	static final Item[] RAW = { Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT, Items.COD, Items.SALMON };

	final FakeMember o;
	final ServerLevel l;
	final SkillRunner r;
	final Autopilot ap;

	Ctx(FakeMember o, Autopilot ap) {
		this.o = o;
		this.l = (ServerLevel) o.level();
		this.r = o.skills();
		this.ap = ap;
	}

	int n(Item i) {
		return o.getInventory().countItem(i);
	}

	int tag(TagKey<Item> t) {
		SimpleContainer c = o.getInventory();
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && s.is(t)) {
				n += s.getCount();
			}
		}
		return n;
	}

	int logs() {
		return tag(ItemTags.LOGS);
	}

	int planks() {
		return tag(ItemTags.PLANKS);
	}

	int sticks() {
		return n(Items.STICK);
	}

	/** 板材に換算した木材（原木1=板材4、棒2=板材1） */
	int woodUnits() {
		return logs() * 4 + planks() + sticks() / 2;
	}

	int cobble() {
		return tag(ItemTags.STONE_TOOL_MATERIALS);
	}

	int ingots() {
		return n(Items.IRON_INGOT);
	}

	int buckets() {
		return n(Items.BUCKET) + n(Items.WATER_BUCKET) + n(Items.LAVA_BUCKET);
	}

	boolean has(Item i) {
		return n(i) > 0;
	}

	/** 0=無し 1=木/金 2=石 3=鉄 4=ダイヤ以上 */
	int tier(TagKey<Item> kind) {
		SimpleContainer c = o.getInventory();
		int best = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty() || !s.is(kind)) {
				continue;
			}
			best = Math.max(best, tierOf(s));
		}
		ItemStack hand = o.getMainHandItem();
		if (!hand.isEmpty() && hand.is(kind)) {
			best = Math.max(best, tierOf(hand));
		}
		return best;
	}

	private static int tierOf(ItemStack s) {
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return id.startsWith("netherite") || id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2 : 1;
	}

	int pickTier() {
		return tier(ItemTags.PICKAXES);
	}

	int axeTier() {
		return tier(ItemTags.AXES);
	}

	int swordTier() {
		return tier(ItemTags.SWORDS);
	}

	int cooked() {
		int n = 0;
		for (Item i : COOKED) {
			n += n(i);
		}
		return n;
	}

	int raw() {
		int n = 0;
		for (Item i : RAW) {
			n += n(i);
		}
		return n;
	}

	Item mostRaw() {
		Item best = null;
		int bn = 0;
		for (Item i : RAW) {
			int c = n(i);
			if (c > bn) {
				bn = c;
				best = i;
			}
		}
		return best;
	}

	boolean fuel() {
		return tag(ItemTags.COALS) > 0 || planks() >= 2 || logs() >= 1;
	}

	int rods() {
		return n(Items.BLAZE_ROD);
	}

	int powder() {
		return n(Items.BLAZE_POWDER);
	}

	int pearls() {
		return n(Items.ENDER_PEARL);
	}

	int eyes() {
		return n(Items.ENDER_EYE);
	}

	/** アイ換算のブレイズ素材（ロッド1=パウダー2） */
	int blazeUnits() {
		return rods() * 2 + powder() + eyes();
	}

	boolean nether() {
		return l.dimension().equals(Level.NETHER);
	}

	boolean end() {
		return l.dimension().equals(Level.END);
	}

	boolean overworld() {
		return l.dimension().equals(Level.OVERWORLD);
	}

	BlockPos mark(String k) {
		return r.mark(k);
	}

	double dist(BlockPos p) {
		return p == null ? Double.MAX_VALUE : Math.sqrt(o.blockPosition().distSqr(p));
	}

	LivingEntity nearest(EntityType<?> type, double range) {
		AABB box = o.getBoundingBox().inflate(range, Math.min(range, 32), range);
		List<LivingEntity> list = l.getEntitiesOfClass(LivingEntity.class, box, e -> e.getType() == type && e.isAlive());
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (LivingEntity e : list) {
			double d = e.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	ServerPlayer nearestPlayer(double range) {
		ServerPlayer best = null;
		double bd = range * range;
		for (ServerPlayer p : l.players()) {
			if (p.isSpectator()) {
				continue;
			}
			double d = p.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}
}
