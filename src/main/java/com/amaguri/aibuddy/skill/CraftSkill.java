package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 持ち物の材料でクラフトする。2x2 は手元、3x3 は近く4ブロック内の作業台（無ければ作って置く）。
 * 板材・棒・作業台が足りなければ原木/板材から自動で作る。1回10tick＋腕振り。
 */
final class CraftSkill extends Skill {
	private static final int ACTION_TICKS = 10;
	private static final double TABLE_RANGE = 4.0;
	/** 作業台を置く行動の目印 */
	private static final Object PLACE = new Object();

	private final String query;
	private final int count;
	private Item target;
	private boolean targetPlanks;
	private List<Recipes.Recipe> candidates = new ArrayList<>();
	private int produced;
	private Object action;
	private int actionTicks;
	private BlockPos table;
	private int total;

	CraftSkill(FakeMember o, SkillRunner r, String item, int count) {
		super(o, r);
		this.query = item == null ? "" : item.trim();
		this.count = Math.max(1, Math.min(64, count));
	}

	@Override
	String begin() {
		target = Recipes.resolveItem(query);
		if (target == null) {
			return "できない: 知らないアイテム " + query;
		}
		targetPlanks = new ItemStack(target).is(ItemTags.PLANKS);
		candidates = targetPlanks ? Recipes.planksRecipes() : Recipes.forOutput(target);
		if (candidates.isEmpty()) {
			return "できない: 作り方を知らない " + Names.item(target);
		}
		String miss = precheck();
		if (miss != null) {
			return miss;
		}
		return null;
	}

	/** 全部作るのに足りない材料を「できない: 材料不足 …」で返す。足りるなら null。 */
	private String precheck() {
		if (targetPlanks) {
			int need = count;
			int have = Inv.count(o, ItemTags.LOGS) * 4;
			if (have < need) {
				return "できない: 材料不足 原木×" + (int) Math.ceil((need - have) / 4.0);
			}
			return null;
		}
		String best = null;
		for (Recipes.Recipe r : candidates) {
			String m = missing(r, (int) Math.ceil(count / (double) r.outCount()));
			if (m == null) {
				return null;
			}
			if (best == null) {
				best = m;
			}
		}
		return "できない: 材料不足 " + best;
	}

	private String missing(Recipes.Recipe r, int times) {
		List<String> parts = new ArrayList<>();
		int planksNeed = 0;
		int stickNeed = 0;
		for (Recipes.Ing ing : r.ings()) {
			int need = ing.n() * times;
			if (ing.tag() == ItemTags.PLANKS) {
				planksNeed += need;
			} else if (ing.item() == Items.STICK) {
				stickNeed += need;
			} else if (ing.have(o) < need) {
				parts.add(ing.label() + "×" + (need - ing.have(o)));
			}
		}
		int stickDef = Math.max(0, stickNeed - Inv.count(o, Items.STICK));
		planksNeed += (int) Math.ceil(stickDef / 4.0) * 2;
		if (r.table() && tableNear() == null && Inv.count(o, Items.CRAFTING_TABLE) == 0) {
			planksNeed += 4;
		}
		int pool = Inv.count(o, ItemTags.PLANKS) + 4 * Inv.count(o, ItemTags.LOGS);
		if (pool < planksNeed) {
			parts.add("板材×" + (planksNeed - pool));
		}
		return parts.isEmpty() ? null : String.join("、", parts);
	}

	/** 近く4ブロック内の作業台。無ければ null。 */
	private BlockPos tableNear() {
		ServerLevel l = SkillUtil.level(o);
		BlockPos c = o.blockPosition();
		BlockPos best = null;
		double bd = TABLE_RANGE * TABLE_RANGE;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos p = c.offset(dx, dy, dz);
					if (!l.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
						continue;
					}
					double d = Vec3.atCenterOf(p).distanceToSqr(o.getEyePosition());
					if (d <= bd) {
						bd = d;
						best = p;
					}
				}
			}
		}
		return best;
	}

	private Recipes.Recipe logToPlanks() {
		for (Recipes.Recipe r : Recipes.planksRecipes()) {
			if (r.ings().get(0).have(o) >= 1) {
				return r;
			}
		}
		return null;
	}

	private Recipes.Recipe pick() {
		if (targetPlanks) {
			return logToPlanks();
		}
		for (Recipes.Recipe r : candidates) {
			if (missing(r, 1) == null) {
				return r;
			}
		}
		return candidates.get(0);
	}

	/** 今やる1手。Recipe（作る）か PLACE（作業台を置く）か String（できない理由）。 */
	private Object next(Recipes.Recipe r, int depth) {
		if (r == null || depth > 4) {
			return "できない: 材料不足 原木";
		}
		for (Recipes.Ing ing : r.ings()) {
			if (ing.have(o) >= ing.n()) {
				continue;
			}
			if (ing.tag() == ItemTags.PLANKS) {
				Recipes.Recipe lr = logToPlanks();
				return lr != null ? lr : "できない: 材料不足 原木";
			}
			if (ing.item() == Items.STICK) {
				return next(Recipes.forOutput(Items.STICK).get(0), depth + 1);
			}
			String m = missing(r, 1);
			return "できない: 材料不足 " + (m == null ? ing.label() : m);
		}
		if (r.table() && tableNear() == null) {
			if (Inv.count(o, Items.CRAFTING_TABLE) > 0) {
				return PLACE;
			}
			return next(Recipes.forOutput(Items.CRAFTING_TABLE).get(0), depth + 1);
		}
		return r;
	}

	@Override
	void tick() {
		total++;
		if (total > 20 * 60 * 3) {
			finish("時間がかかりすぎたのでやめた", null);
			return;
		}
		if (action == null) {
			Object a = next(pick(), 0);
			if (a instanceof String err) {
				finish(err, null);
				return;
			}
			action = a;
			actionTicks = 0;
		}
		actionTicks++;
		if (actionTicks == 1 || actionTicks == 6) {
			SkillUtil.swing(o);
		}
		BlockPos tb = tableNear();
		if (tb != null) {
			table = tb;
			SkillUtil.lookAt(o, Vec3.atCenterOf(tb));
		}
		if (actionTicks < ACTION_TICKS) {
			return;
		}
		Object done = action;
		action = null;
		if (done == PLACE) {
			if (!placeTable()) {
				finish("できない: 作業台を置く場所が無い", null);
			}
			return;
		}
		Recipes.Recipe r = (Recipes.Recipe) done;
		for (Recipes.Ing ing : r.ings()) {
			if (!ing.take(o, 1)) {
				finish("できない: 材料不足 " + ing.label(), null);
				return;
			}
		}
		Inv.add(o, new ItemStack(r.out(), r.outCount()));
		if (r.out() == target || (targetPlanks && new ItemStack(r.out()).is(ItemTags.PLANKS))) {
			produced += r.outCount();
		}
		if (produced >= count) {
			finish("作った: " + Names.item(target) + "×" + produced, null);
		}
	}

	/** 足元の周りに作業台を置く。 */
	private boolean placeTable() {
		ServerLevel l = SkillUtil.level(o);
		BlockPos me = o.blockPosition();
		for (int r = 1; r <= 2; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos p = me.offset(dx, 0, dz);
					BlockState here = l.getBlockState(p);
					boolean free = here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty());
					if (!free || !l.getBlockState(p.below()).blocksMotion() || l.getBlockState(p.below()).getBlock() == Blocks.CRAFTING_TABLE) {
						continue;
					}
					if (!Inv.remove(o, Items.CRAFTING_TABLE, 1)) {
						return false;
					}
					BlockState st = Blocks.CRAFTING_TABLE.defaultBlockState();
					l.setBlock(p, st, 3);
					l.playSound(null, p, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
					table = p;
					SkillUtil.lookAt(o, Vec3.atCenterOf(p));
					return true;
				}
			}
		}
		return false;
	}

	@Override
	String status() {
		return Names.item(target == null ? Items.AIR : target) + "を作っている(" + produced + "/" + count + ")";
	}
}
