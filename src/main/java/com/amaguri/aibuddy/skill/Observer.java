package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 周囲の要約（200字程度）。 */
final class Observer {
	private Observer() {
	}

	static String observe(FakeMember o) {
		if (!(o.level() instanceof ServerLevel l)) {
			return "世界にいない";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("体力").append(Math.round(o.getHealth())).append('/').append(Math.round(o.getMaxHealth()));
		// UNVERIFIED: FakeMember は PathfinderMob なので満腹度（FoodData）が無い。代わりに食料の数を出す
		sb.append(" 食料").append(foodCount(o));
		sb.append(" 手持ち:").append(topItems(o));
		sb.append(" 近くの人:").append(players(o, l));
		sb.append(" 敵:").append(enemies(o, l));
		sb.append(" 鉱石:").append(ores(o, l));
		sb.append(' ').append(timeText(l)).append(' ').append(dimText(l)).append(" 座標")
				.append(SkillUtil.pos(o.blockPosition()));
		String s = sb.toString();
		return s.length() > 300 ? s.substring(0, 300) : s;
	}

	static int foodCount(FakeMember o) {
		int n = 0;
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack st = o.getInventory().getItem(i);
			if (EatSkill.isFood(st)) {
				n += st.getCount();
			}
		}
		return n;
	}

	private static String topItems(FakeMember o) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack st = o.getInventory().getItem(i);
			if (!st.isEmpty()) {
				counts.merge(st.getItem(), st.getCount(), Integer::sum);
			}
		}
		if (counts.isEmpty()) {
			return "なし";
		}
		List<Map.Entry<Item, Integer>> list = new ArrayList<>(counts.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(8, list.size()); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(Names.item(list.get(i).getKey())).append(list.get(i).getValue());
		}
		return sb.toString();
	}

	private static String players(FakeMember o, ServerLevel l) {
		List<String> out = new ArrayList<>();
		List<ServerPlayer> ps = new ArrayList<>(l.players());
		ps.removeIf(ServerPlayer::isSpectator);
		ps.sort((a, b) -> Double.compare(a.distanceToSqr(o), b.distanceToSqr(o)));
		for (int i = 0; i < Math.min(3, ps.size()); i++) {
			out.add(ps.get(i).getScoreboardName() + Math.round(Math.sqrt(ps.get(i).distanceToSqr(o))) + "m");
		}
		return out.isEmpty() ? "なし" : String.join(",", out);
	}

	private static String enemies(FakeMember o, ServerLevel l) {
		Map<EntityType<?>, int[]> m = new LinkedHashMap<>();
		List<LivingEntity> list = l.getEntitiesOfClass(LivingEntity.class, o.getBoundingBox().inflate(20, 8, 20),
				e -> e instanceof Enemy && e.isAlive());
		list.sort((a, b) -> Double.compare(a.distanceToSqr(o), b.distanceToSqr(o)));
		for (LivingEntity e : list) {
			int[] v = m.computeIfAbsent(e.getType(), k -> new int[] { 0, (int) Math.round(Math.sqrt(e.distanceToSqr(o))) });
			v[0]++;
		}
		if (m.isEmpty()) {
			return "なし";
		}
		List<String> out = new ArrayList<>();
		for (Map.Entry<EntityType<?>, int[]> en : m.entrySet()) {
			if (out.size() >= 3) {
				break;
			}
			out.add(Names.entity(en.getKey()) + "x" + en.getValue()[0] + "(" + en.getValue()[1] + "m)");
		}
		return String.join(",", out);
	}

	static boolean isOre(BlockState s) {
		String p = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return p.endsWith("_ore") || p.equals("ancient_debris");
	}

	private static String ores(FakeMember o, ServerLevel l) {
		BlockPos c = o.blockPosition();
		Map<Block, double[]> best = new LinkedHashMap<>();
		int r = 10;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (!l.hasChunkAt(c.offset(dx, 0, dz))) {
					continue;
				}
				for (int dy = -8; dy <= 8; dy++) {
					BlockPos p = c.offset(dx, dy, dz);
					BlockState s = l.getBlockState(p);
					if (s.isAir() || !isOre(s)) {
						continue;
					}
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					double[] cur = best.get(s.getBlock());
					if (cur == null || d < cur[0]) {
						best.put(s.getBlock(), new double[] { d, dy });
					}
				}
			}
		}
		if (best.isEmpty()) {
			return "なし";
		}
		List<Map.Entry<Block, double[]>> list = new ArrayList<>(best.entrySet());
		list.sort((a, b) -> Double.compare(a.getValue()[0], b.getValue()[0]));
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(3, list.size()); i++) {
			double[] v = list.get(i).getValue();
			String vert = v[1] <= -3 ? "下" : v[1] >= 3 ? "上" : "";
			out.add(Names.block(list.get(i).getKey()) + Math.round(v[0]) + "m" + vert);
		}
		return String.join(",", out);
	}

	static boolean isNight(ServerLevel l) {
		if (!l.dimension().equals(Level.OVERWORLD)) {
			return false;
		}
		long t = Math.floorMod(l.getOverworldClockTime(), 24000L);
		return t >= 13000 && t < 23000;
	}

	private static String timeText(ServerLevel l) {
		if (!l.dimension().equals(Level.OVERWORLD)) {
			return "";
		}
		long t = Math.floorMod(l.getOverworldClockTime(), 24000L);
		if (t < 6000) {
			return "朝";
		}
		if (t < 12000) {
			return "昼";
		}
		if (t < 13000) {
			return "夕方";
		}
		return t < 23000 ? "夜" : "明け方";
	}

	public static String dimText(ServerLevel l) {
		if (l.dimension().equals(Level.NETHER)) {
			return "ネザー";
		}
		if (l.dimension().equals(Level.END)) {
			return "エンド";
		}
		return "オーバーワールド";
	}
}
