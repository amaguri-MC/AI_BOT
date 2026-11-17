package com.amaguri.aibuddy.skill;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 近くの指定ブロックを探して歩いて行き、掘る。埋まっていれば手前のブロックから掘り進む。
 * 掘る速さはダイヤのツルハシ相当（Digger）。壊したら落ちた物を拾う。
 */
final class MineSkill extends Skill {
	private enum State { FIND, GOTO, DIG, LOOT, ROAM }

	private static final Map<String, String> ALIAS = new HashMap<>();

	static {
		String[] kv = {
			"ダイヤ", "diamond", "ダイヤモンド", "diamond", "鉄", "iron", "金", "gold", "石炭", "coal", "銅", "copper",
			"ラピス", "lapis", "青金石", "lapis", "レッドストーン", "redstone", "エメラルド", "emerald",
			"古代の残骸", "ancient_debris", "ネザライト", "ancient_debris", "石英", "nether_quartz", "クォーツ", "nether_quartz",
			"黒曜石", "obsidian", "木", "log", "原木", "log", "丸太", "log", "石", "stone", "砂", "sand", "土", "dirt", "砂利", "gravel",
			"鉱石", "ore", "鉱石全般", "ore", "wood", "log", "logs", "log", "ores", "ore",
		};
		for (int i = 0; i + 1 < kv.length; i += 2) {
			ALIAS.put(kv[i], kv[i + 1]);
		}
	}

	private final String query;
	private final int goal;
	private Set<Block> blocks = new HashSet<>();
	private String label;
	private State state = State.FIND;
	private BlockPos target;
	private boolean digIsTarget;
	private int obstacles;
	private int mined;
	private int radius = 20;
	private int roams;
	private int lootTicks;
	private BlockPos lastMined;
	private int ticks;
	private final Set<Long> blacklist = new HashSet<>();

	MineSkill(FakeMember o, SkillRunner r, String query, int count) {
		super(o, r);
		this.query = query == null ? "" : query.trim();
		this.goal = Math.max(1, Math.min(64, count));
	}

	@Override
	String begin() {
		resolve();
		if (blocks.isEmpty()) {
			return "できない: 知らないブロック " + query;
		}
		nav.setVertTol(0.6);
		return null;
	}

	private void resolve() {
		String q = query.toLowerCase();
		q = ALIAS.getOrDefault(q, ALIAS.getOrDefault(query, q));
		if (q.startsWith("minecraft:")) {
			q = q.substring("minecraft:".length());
		}
		Set<Block> set = new HashSet<>();
		for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
			String p = id.getPath();
			boolean hit;
			switch (q) {
				case "ore" -> hit = p.endsWith("_ore") || p.equals("ancient_debris");
				case "log" -> hit = p.endsWith("_log") || p.endsWith("_stem");
				case "nether_quartz" -> hit = p.equals("nether_quartz_ore");
				case "stone" -> hit = p.equals("stone");
				default -> hit = p.equals(q) || p.equals(q + "_ore") || p.equals("deepslate_" + q + "_ore")
						|| (q.endsWith("_ore") && p.equals("deepslate_" + q));
			}
			if (hit) {
				set.add(BuiltInRegistries.BLOCK.getValue(id));
			}
		}
		if (set.isEmpty() && q.length() >= 3) {
			// 部分一致（例: "oak_log" の一部など）。ID に含まれるブロックだけ
			for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
				if (id.getPath().contains(q)) {
					set.add(BuiltInRegistries.BLOCK.getValue(id));
				}
			}
		}
		blocks = set;
		if (q.equals("ore")) {
			label = "鉱石";
		} else if (q.equals("log")) {
			label = "原木";
		} else if (!set.isEmpty()) {
			Block first = set.iterator().next();
			String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
			String shown = path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
			label = Names.raw(shown, Names.block(first));
		} else {
			label = query;
		}
	}

	@Override
	void tick() {
		ticks++;
		if (ticks > 20 * 60 * 10) {
			finishNow("時間がかかりすぎたのでやめた");
			return;
		}
		switch (state) {
			case FIND -> find();
			case GOTO -> goTo();
			case DIG -> dig();
			case LOOT -> loot();
			case ROAM -> roam();
		}
	}

	private void finishNow(String why) {
		String text = label + "を" + mined + "個掘った" + (why == null ? "" : "（" + why + "）");
		finish(text, "掘り終わった: " + text);
	}

	// ---- FIND ----

	private void find() {
		BlockPos p = scan(radius);
		if (p != null) {
			target = p;
			obstacles = 0;
			state = State.GOTO;
			return;
		}
		if (radius < 36) {
			radius = 36;
			return;
		}
		if (++roams > 4) {
			finishNow(mined == 0 ? "近くに見つからなかった" : "これ以上見つからなかった");
			return;
		}
		Vec3 pos = o.position();
		double a = o.getRandom().nextDouble() * Math.PI * 2;
		Vec3 t = SkillUtil.snap(o, pos.x + Math.cos(a) * 30, pos.z + Math.sin(a) * 30, pos.y);
		nav.setVertTol(3.0);
		nav.go(t, 3.0, 1.2);
		state = State.ROAM;
	}

	private void roam() {
		nav.tick();
		if (nav.arrived() || nav.failed()) {
			radius = 24;
			nav.setVertTol(0.6);
			nav.stop();
			state = State.FIND;
		}
	}

	private BlockPos scan(int r) {
		ServerLevel l = SkillUtil.level(o);
		BlockPos c = o.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (!l.hasChunkAt(c.offset(dx, 0, dz))) {
					continue;
				}
				for (int dy = -16; dy <= 16; dy++) {
					BlockPos p = c.offset(dx, dy, dz);
					BlockState s = l.getBlockState(p);
					if (!blocks.contains(s.getBlock()) || blacklist.contains(p.asLong())) {
						continue;
					}
					double d = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
					if (d > bestScore) {
						continue;
					}
					if (SkillUtil.nearLava(l, p)) {
						continue;
					}
					boolean exposed = false;
					for (Direction dir : Direction.values()) {
						if (!l.getBlockState(p.relative(dir)).blocksMotion()) {
							exposed = true;
							break;
						}
					}
					double score = d + (exposed ? 0 : 4);
					if (score < bestScore) {
						bestScore = score;
						best = p;
					}
				}
			}
		}
		return best;
	}

	// ---- GOTO ----

	private boolean stillThere() {
		return blocks.contains(SkillUtil.level(o).getBlockState(target).getBlock());
	}

	private void goTo() {
		if (!stillThere()) {
			state = State.FIND;
			return;
		}
		Vec3 c = Vec3.atCenterOf(target);
		if (o.getEyePosition().distanceTo(c) <= 4.3) {
			nav.stop();
			state = State.DIG;
			return;
		}
		nav.go(c, 0.9, 1.1);
		nav.tick();
		if (nav.failed()) {
			blacklist.add(target.asLong());
			nav.stop();
			state = State.FIND;
		}
	}

	// ---- DIG ----

	private void dig() {
		ServerLevel l = SkillUtil.level(o);
		if (!digger.active()) {
			if (!stillThere()) {
				state = State.FIND;
				return;
			}
			Vec3 eye = o.getEyePosition();
			Vec3 c = Vec3.atCenterOf(target);
			if (eye.distanceTo(c) > 5.2) {
				state = State.GOTO;
				return;
			}
			BlockHitResult hit = l.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
			BlockPos first = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : target;
			digIsTarget = first.equals(target) || blocks.contains(l.getBlockState(first).getBlock());
			if (digIsTarget) {
				target = first;
			} else if (++obstacles > 30 || SkillUtil.nearLava(l, first) || SkillUtil.isLiquidBlock(l.getBlockState(first))) {
				blacklist.add(target.asLong());
				state = State.FIND;
				return;
			}
			if (!digger.begin(first)) {
				blacklist.add(target.asLong());
				state = State.FIND;
				return;
			}
		}
		int r = digger.tick();
		if (r == Digger.BROKEN) {
			if (digIsTarget) {
				mined++;
				lastMined = target;
				lootTicks = 0;
				state = State.LOOT;
			}
		} else if (r == Digger.ABORTED) {
			state = State.GOTO;
		}
	}

	// ---- LOOT ----

	private void loot() {
		lootTicks++;
		List<ItemEntity> items = SkillUtil.items(o, 7);
		if (!items.isEmpty() && lootTicks < 80) {
			nav.setVertTol(3.0);
			nav.go(items.get(0).position(), 0.6, 1.2);
			nav.tick();
			if (nav.failed()) {
				lootTicks = 80;
			}
			return;
		}
		if (lootTicks < 8) {
			return;
		}
		nav.stop();
		nav.setVertTol(0.6);
		if (mined >= goal) {
			finishNow(null);
		} else {
			radius = 20;
			roams = 0;
			state = State.FIND;
		}
	}

	@Override
	String status() {
		return label + "を掘っている(" + mined + "/" + goal + ")";
	}
}
