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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 近くの指定ブロックを探して歩いて行き、掘る。埋まっていれば手前のブロックから掘り進む。
 * 掘る速さ・ドロップ・耐久の減りはバニラのプレイヤーと同じ（Digger）。適正道具が無い鉱石は掘らない。壊したら落ちた物を拾う。
 */
final class MineSkill extends Skill {
	private enum State { FIND, GOTO, DIG, LOOT, ROAM }

	private static final Map<String, String> ALIAS = new HashMap<>();

	static {
		String[] kv = {
			"ダイヤ", "diamond", "ダイヤモンド", "diamond", "鉄", "iron", "金", "gold", "石炭", "coal", "銅", "copper",
			"ラピス", "lapis", "青金石", "lapis", "レッドストーン", "redstone", "エメラルド", "emerald",
			"古代の残骸", "ancient_debris", "ネザライト", "ancient_debris", "石英", "nether_quartz", "クォーツ", "nether_quartz",
			"黒曜石", "obsidian", "木", "log", "原木", "log", "丸太", "log", "石", "stone", "丸石", "stone", "cobblestone", "stone", "砂", "sand", "土", "dirt", "砂利", "gravel",
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
	/** 届かなかった鉱石 → 解除される ticks（60秒）。 */
	private final Map<Long, Integer> blacklist = new HashMap<>();
	/** 今の目標を選んでから、掘り始められないまま経過した tick（10秒で諦める）。 */
	private int reachTicks;
	private boolean extraOre;
	private boolean lootDig;
	private int lootItem = -1;
	private int lootItemTicks;
	private final Set<Integer> lootIgnore = new HashSet<>();
	private int reachLimit = 200;

	private void ban(BlockPos p) {
		blacklist.put(p.asLong(), ticks + 20 * 60);
	}

	private boolean banned(BlockPos p) {
		Integer until = blacklist.get(p.asLong());
		if (until == null) {
			return false;
		}
		if (until <= ticks) {
			blacklist.remove(p.asLong());
			return false;
		}
		return true;
	}

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
		String need = missingTool();
		if (need != null) {
			return "できない: 道具が足りない " + need;
		}
		nav.setVertTol(0.6);
		return null;
	}

	/** 持ち物の道具で適正に掘れるか（ドロップが出るか）。 */
	private boolean harvestable(BlockState s) {
		if (!s.requiresCorrectToolForDrops()) {
			return true;
		}
		ItemStack t = Inv.bestTool(o, s);
		return !t.isEmpty() && t.isCorrectToolForDrops(s);
	}

	/** 対象が全部「適正道具が必要」で今の道具では1つも掘れないとき、必要な最低限の道具名を返す。掘れるなら null。 */
	private String missingTool() {
		Item[] picks = { Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE };
		int lowest = Integer.MAX_VALUE;
		for (Block b : blocks) {
			BlockState s = b.defaultBlockState();
			if (harvestable(s)) {
				return null;
			}
			for (int i = 0; i < picks.length; i++) {
				if (new ItemStack(picks[i]).isCorrectToolForDrops(s)) {
					lowest = Math.min(lowest, i);
					break;
				}
			}
		}
		return lowest == Integer.MAX_VALUE ? null : Names.item(picks[lowest]);
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
			reachTicks = 0;
			// 10秒＋遠い分の歩く時間
			reachLimit = 200 + (int) (o.position().distanceTo(Vec3.atCenterOf(p)) * 12);
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
					if (!blocks.contains(s.getBlock()) || banned(p)) {
						continue;
					}
					if (!harvestable(s)) {
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
		// 目標は掘り終わるか諦めるまで固定。10秒たっても掘れる距離に入れなければ60秒ブラックリスト
		if (++reachTicks > reachLimit) {
			ban(target);
			nav.stop();
			state = State.FIND;
			return;
		}
		nav.go(c, 0.9, 1.1);
		nav.tick();
		if (nav.failed()) {
			ban(target);
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
				if (++reachTicks > reachLimit) {
					ban(target);
					state = State.FIND;
					return;
				}
				state = State.GOTO;
				return;
			}
			BlockHitResult hit = l.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
			BlockPos first = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : target;
			extraOre = false;
			digIsTarget = first.equals(target) || blocks.contains(l.getBlockState(first).getBlock());
			if (digIsTarget && !harvestable(l.getBlockState(first))) {
				// 道具が壊れた等で、もう適正に掘れない
				ban(target);
				state = State.FIND;
				return;
			}
			if (digIsTarget) {
				// 別の鉱石が手前にあっても目標は入れ替えない（掘った分は数える）
				if (!first.equals(target)) {
					digIsTarget = false;
					extraOre = true;
				}
			} else if (++obstacles > 30 || SkillUtil.nearLava(l, first) || SkillUtil.isLiquidBlock(l.getBlockState(first))) {
				ban(target);
				state = State.FIND;
				return;
			}
			if (!digger.begin(first)) {
				ban(target);
				state = State.FIND;
				return;
			}
		}
		int r = digger.tick();
		if (r == Digger.BROKEN) {
			if (digIsTarget || extraOre) {
				mined++;
				extraOre = false;
				lastMined = target;
				lootTicks = 0;
				lootDig = false;
				state = State.LOOT;
			}
		} else if (r == Digger.ABORTED) {
			if (++reachTicks > reachLimit) {
				ban(target);
				state = State.FIND;
				return;
			}
			state = State.GOTO;
		}
	}

	// ---- LOOT ----

	private void loot() {
		lootTicks++;
		// 拾えない物（5秒かけても届かない）は諦めて無視リストへ。無視していない物が残る間は完了にしない。
		ItemEntity it = null;
		for (ItemEntity e : SkillUtil.items(o, 7)) {
			if (!lootIgnore.contains(e.getId())) {
				it = e;
				break;
			}
		}
		if (it != null) {
			if (it.getId() != lootItem) {
				lootItem = it.getId();
				lootItemTicks = 0;
				lootDig = false;
				digger.cancel();
			}
			if (++lootItemTicks > 100) {
				lootIgnore.add(it.getId());
				lootItem = -1;
				digger.cancel();
				nav.stop();
				return;
			}
			if (lootDig) {
				// 落ちた物が壁の中の穴に閉じ込められている: 間の壁を掘って近づく
				if (digger.active()) {
					int r = digger.tick();
					if (r == Digger.BROKEN || r == Digger.ABORTED) {
						lootDig = false;
					}
					return;
				}
				Vec3 eye = o.getEyePosition();
				BlockHitResult hit = SkillUtil.level(o).clip(new ClipContext(eye, it.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
				if (hit.getType() == HitResult.Type.BLOCK && digger.begin(hit.getBlockPos())) {
					return;
				}
				// 目の高さからは見えても足元の壁で近づけない（頭の高さの穴に落ちた物）: 足の高さの壁を掘って近づく
				Vec3 feet = o.position().add(0, 0.3, 0);
				BlockHitResult low = SkillUtil.level(o).clip(new ClipContext(feet, it.position().add(0, 0.1, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
				if (low.getType() == HitResult.Type.BLOCK && digger.begin(low.getBlockPos())) {
					return;
				}
				lootDig = false;
			}
			nav.setVertTol(3.0);
			nav.go(it.position(), 0.6, 1.2);
			nav.tick();
			if (nav.failed()) {
				nav.stop();
				lootDig = true;
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
