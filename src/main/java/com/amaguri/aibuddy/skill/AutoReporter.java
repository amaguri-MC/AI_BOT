package com.amaguri.aibuddy.skill;

import java.util.HashSet;
import java.util.Set;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * スキル外でも tick で監視して出来事を postEvent する。同じ報告は繰り返さない。
 * 構造物 / 重要アイテム / 次元移動 / 体力 / 夜。
 */
final class AutoReporter {
	private record Watch(ResourceKey<Structure> key, String label) {
	}

	private static final Watch[] WATCHES = {
		new Watch(BuiltinStructures.STRONGHOLD, "要塞"),
		new Watch(BuiltinStructures.FORTRESS, "ネザー要塞"),
		new Watch(BuiltinStructures.BASTION_REMNANT, "砦"),
	};

	private final FakeMember o;
	private final Set<String> reportedStructures = new HashSet<>();

	private ResourceKey<Level> lastDim;
	private boolean lowHealthArmed = true;
	private boolean nightArmed = true;

	private static final class Counter {
		int last = -1;
		int pendingAt = -1;
	}

	private final Counter rods = new Counter();
	private final Counter pearls = new Counter();
	private final Counter eyes = new Counter();

	/** 次元移動で作り直されたとき、報告済みの記録を引き継ぐ。 */
	void inheritFrom(AutoReporter prev) {
		reportedStructures.addAll(prev.reportedStructures);
		rods.last = prev.rods.last;
		pearls.last = prev.pearls.last;
		eyes.last = prev.eyes.last;
		nightArmed = prev.nightArmed;
		lowHealthArmed = prev.lowHealthArmed;
	}

	AutoReporter(FakeMember o) {
		this.o = o;
	}

	void tick() {
		if (!(o.level() instanceof ServerLevel l)) {
			return;
		}
		int t = o.tickCount;
		if (t % 10 == 0) {
			items(t);
			dimension(l);
			health();
			night(l);
		}
		if (t % 40 == 7) {
			structures(l);
		}
	}

	private void dimension(ServerLevel l) {
		ResourceKey<Level> d = l.dimension();
		if (lastDim != null && !d.equals(lastDim)) {
			o.postEvent(Observer.dimText(l) + "に移動した");
			// 次元が変わったら構造物の報告は別物として扱う
		}
		lastDim = d;
	}

	private void health() {
		float h = o.getHealth();
		if (h <= 6 && lowHealthArmed) {
			lowHealthArmed = false;
			o.postEvent("体力が少ない " + Math.round(h) + "/" + Math.round(o.getMaxHealth()));
		} else if (h > 10) {
			lowHealthArmed = true;
		}
	}

	private void night(ServerLevel l) {
		boolean night = Observer.isNight(l);
		if (night && nightArmed) {
			nightArmed = false;
			o.postEvent("夜になった");
		} else if (!night && l.dimension().equals(Level.OVERWORLD)) {
			nightArmed = true;
		}
	}

	private void items(int t) {
		report(t, Items.BLAZE_ROD, rods, "ブレロ ", "本");
		report(t, Items.ENDER_PEARL, pearls, "エンダーパール ", "個");
		report(t, Items.ENDER_EYE, eyes, "エンダーアイ ", "個");
	}

	/** 増えたら、20tick 静かになってから1回だけ「名前 n単位」と報告する（連続で拾ってもまとめる）。 */
	private void report(int t, Item item, Counter c, String label, String unit) {
		int now = count(item);
		if (c.last < 0) {
			c.last = now;
			return;
		}
		if (now > c.last) {
			c.pendingAt = t;
		}
		c.last = now;
		if (c.pendingAt >= 0 && t - c.pendingAt >= 20) {
			c.pendingAt = -1;
			if (now > 0) {
				o.postEvent(label + now + unit);
			}
		}
	}

	private int count(Item item) {
		return o.getInventory().countItem(item);
	}

	private void structures(ServerLevel l) {
		// UNVERIFIED: getStructureAt は読み込み済みチャンクの構造物開始位置を参照する想定（hasChunkAt で保護）
		var reg = l.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		BlockPos c = o.blockPosition();
		int[][] ring = {
			{ 0, 0 }, { 24, 0 }, { -24, 0 }, { 0, 24 }, { 0, -24 }, { 17, 17 }, { -17, 17 }, { 17, -17 }, { -17, -17 },
			{ 40, 0 }, { -40, 0 }, { 0, 40 }, { 0, -40 },
		};
		for (Watch w : WATCHES) {
			Structure st = reg.getValueOrThrow(w.key());
			for (int[] r : ring) {
				BlockPos p = c.offset(r[0], 0, r[1]);
				if (!l.hasChunkAt(p)) {
					continue;
				}
				StructureStart start = l.structureManager().getStructureAt(p, st);
				if (start == null || !start.isValid()) {
					continue;
				}
				BlockPos center = start.getBoundingBox().getCenter();
				String key = w.label() + "@" + start.getChunkPos().x() + "," + start.getChunkPos().z();
				if (reportedStructures.add(key)) {
					boolean inside = r[0] == 0 && r[1] == 0;
					o.postEvent(w.label() + "あった" + (inside ? "（中にいる）" : "") + " 座標 " + SkillUtil.pos(center));
				}
				break;
			}
		}
	}
}
