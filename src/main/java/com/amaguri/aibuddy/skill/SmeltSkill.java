package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * かまどで焼く・精錬する。かまどを（無ければ作って）置き、燃料（石炭/木炭/板材/原木/棒）で1個200tick、かまどの前で待つ。
 * 入力でも出力でも指定できる（"raw_iron" も "iron_ingot" も "鉄" も可）。
 */
final class SmeltSkill extends Skill {
	private enum State { CRAFT_FURNACE, PLACE, GO, COOK, DONE }

	private static final int PER_ITEM = 200;
	private static final double STAND = 3.0;

	private final String query;
	private final int wanted;
	private Recipes.Smelt smelt;
	private int count;
	private State state = State.PLACE;
	private BlockPos furnace;
	private CraftSkill sub;
	private int cookTicks;
	private int burnLeft;
	private int made;
	private int guard;

	SmeltSkill(FakeMember o, SkillRunner r, String item, int count) {
		super(o, r);
		this.query = item == null ? "" : item.trim();
		this.wanted = Math.max(1, Math.min(64, count));
	}

	@Override
	String begin() {
		Item q = Recipes.resolveItem(query);
		if (q == null) {
			return "できない: 知らないアイテム " + query;
		}
		List<Recipes.Smelt> opts = Recipes.smeltsFor(q);
		if (opts.isEmpty()) {
			return "できない: 焼けない・精錬できない " + Names.item(q);
		}
		// 手持ちの入力があるものを優先
		Recipes.Smelt pick = opts.get(0);
		int best = -1;
		for (Recipes.Smelt s : opts) {
			int c = Inv.count(o, s.in());
			if (c > best) {
				best = c;
				pick = s;
			}
		}
		smelt = pick;
		if (best <= 0) {
			return "できない: 材料不足 " + Recipes.label(smelt.in()) + "×" + wanted;
		}
		count = Math.min(wanted, best);
		int needTicks = count * PER_ITEM;
		int fuel = fuelTotal();
		if (fuel < needTicks) {
			int def = needTicks - fuel;
			return "できない: 燃料不足 石炭×" + (int) Math.ceil(def / 1600.0);
		}
		furnace = nearFurnace();
		if (furnace == null && Inv.count(o, Items.FURNACE) == 0) {
			int cobble = Inv.count(o, ItemTags.STONE_CRAFTING_MATERIALS);
			if (cobble < 8) {
				return "できない: 材料不足 丸石×" + (8 - cobble);
			}
			sub = new CraftSkill(o, runner, "furnace", 1);
			String err = sub.begin();
			if (err != null) {
				return err;
			}
			state = State.CRAFT_FURNACE;
		} else if (furnace != null) {
			state = State.GO;
		}
		return null;
	}

	/** 手持ちの燃料で燃やせる合計tick（原木は他に使うので、石炭・木炭・板材・棒を優先して数え、足りなければ原木も数える）。 */
	private int fuelTotal() {
		int t = 0;
		SimpleContainer c = o.getInventory();
		for (int k = 0; k < c.getContainerSize(); k++) {
			ItemStack st = c.getItem(k);
			t += Recipes.fuelTicks(st) * st.getCount();
		}
		return t;
	}

	private BlockPos nearFurnace() {
		ServerLevel l = SkillUtil.level(o);
		BlockPos c = o.blockPosition();
		BlockPos best = null;
		double bd = 6.0 * 6.0;
		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = -6; dz <= 6; dz++) {
				for (int dy = -3; dy <= 3; dy++) {
					BlockPos p = c.offset(dx, dy, dz);
					if (!l.getBlockState(p).is(Blocks.FURNACE)) {
						continue;
					}
					double d = Vec3.atCenterOf(p).distanceToSqr(o.getEyePosition());
					if (d < bd) {
						bd = d;
						best = p;
					}
				}
			}
		}
		return best;
	}

	@Override
	void tick() {
		if (++guard > 20 * 60 * 15) {
			finishNow("時間がかかりすぎたのでやめた");
			return;
		}
		switch (state) {
			case CRAFT_FURNACE -> {
				sub.tick();
				if (sub.finished) {
					if (sub.result != null && sub.result.startsWith("できない")) {
						finish(sub.result, null);
						return;
					}
					state = State.PLACE;
				}
			}
			case PLACE -> place();
			case GO -> go();
			case COOK -> cook();
			case DONE -> {
			}
		}
	}

	private void place() {
		ServerLevel l = SkillUtil.level(o);
		if (furnace != null && l.getBlockState(furnace).is(Blocks.FURNACE)) {
			state = State.GO;
			return;
		}
		if (Inv.count(o, Items.FURNACE) == 0) {
			finish("できない: かまどが無い", null);
			return;
		}
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
					if (!free || !l.getBlockState(p.below()).blocksMotion()) {
						continue;
					}
					Inv.remove(o, Items.FURNACE, 1);
					BlockState st = Blocks.FURNACE.defaultBlockState();
					l.setBlock(p, st, 3);
					l.playSound(null, p, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
					SkillUtil.swing(o);
					furnace = p;
					state = State.GO;
					return;
				}
			}
		}
		finish("できない: かまどを置く場所が無い", null);
	}

	private void go() {
		ServerLevel l = SkillUtil.level(o);
		if (!l.getBlockState(furnace).is(Blocks.FURNACE)) {
			finish("できない: かまどが壊れた", null);
			return;
		}
		Vec3 c = Vec3.atCenterOf(furnace);
		if (o.getEyePosition().distanceTo(c) <= STAND) {
			nav.stop();
			state = State.COOK;
			return;
		}
		nav.go(c, 2.2, 1.1);
		nav.tick();
		if (nav.failed()) {
			finish("できない: かまどに近づけない", null);
		}
	}

	private void cook() {
		ServerLevel l = SkillUtil.level(o);
		BlockState st = l.getBlockState(furnace);
		if (!st.is(Blocks.FURNACE)) {
			finishNow("かまどが壊れた");
			return;
		}
		if (made >= count) {
			finishNow(null);
			return;
		}
		SkillUtil.lookAt(o, Vec3.atCenterOf(furnace));
		if (burnLeft <= 0) {
			if (!takeFuel()) {
				finishNow("燃料が切れた");
				return;
			}
		}
		if (cookTicks == 0) {
			if (Inv.count(o, smelt.in()) < 1) {
				finishNow("材料が無くなった");
				return;
			}
			setLit(l, st, true);
		}
		burnLeft--;
		cookTicks++;
		if (cookTicks % 40 == 0) {
			SkillUtil.swing(o);
			l.playSound(null, furnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.6f, 1.0f);
		}
		if (cookTicks % 10 == 0) {
			l.sendParticles(ParticleTypes.SMOKE, furnace.getX() + 0.5, furnace.getY() + 1.1, furnace.getZ() + 0.5, 1, 0.1, 0.0, 0.1, 0.01);
		}
		if (cookTicks >= PER_ITEM) {
			cookTicks = 0;
			if (Inv.remove(o, smelt.in(), 1)) {
				Inv.add(o, new ItemStack(smelt.out(), 1));
				made++;
			}
			if (made >= count) {
				finishNow(null);
			}
		}
	}

	/** 燃料を1つ使い、燃える時間を足す。優先: 石炭・木炭 → 棒 → 板材 → 原木。 */
	private boolean takeFuel() {
		SimpleContainer c = o.getInventory();
		int bestTicks = -1;
		int slot = -1;
		for (int pass = 0; pass < 2 && slot < 0; pass++) {
			for (int k = 0; k < c.getContainerSize(); k++) {
				ItemStack st = c.getItem(k);
				int t = Recipes.fuelTicks(st);
				if (t <= 0) {
					continue;
				}
				boolean isLog = st.is(ItemTags.LOGS_THAT_BURN);
				// 1周目は原木以外、2周目で原木
				if ((pass == 0) == isLog) {
					continue;
				}
				// 精錬する素材そのもの（原木→木炭）は燃料にしない
				if (st.is(smelt.in())) {
					continue;
				}
				if (t > bestTicks) {
					bestTicks = t;
					slot = k;
				}
			}
		}
		if (slot < 0) {
			return false;
		}
		ItemStack st = c.getItem(slot);
		st.shrink(1);
		if (st.isEmpty()) {
			c.setItem(slot, ItemStack.EMPTY);
		}
		burnLeft += bestTicks;
		return true;
	}

	private void setLit(ServerLevel l, BlockState st, boolean lit) {
		// UNVERIFIED: AbstractFurnaceBlock.LIT は BooleanProperty。かまどの火の見た目だけ。
		if (st.hasProperty(AbstractFurnaceBlock.LIT) && st.getValue(AbstractFurnaceBlock.LIT) != lit) {
			l.setBlock(furnace, st.setValue(AbstractFurnaceBlock.LIT, lit), 3);
		}
	}

	private void finishNow(String why) {
		state = State.DONE;
		if (furnace != null) {
			ServerLevel l = SkillUtil.level(o);
			BlockState st = l.getBlockState(furnace);
			if (st.is(Blocks.FURNACE)) {
				setLit(l, st, false);
			}
		}
		String what = Recipes.label(smelt.out());
		finish("焼いた: " + what + "×" + made + (why == null ? "" : "（" + why + "）"), null);
	}

	@Override
	void onStop() {
		super.onStop();
		if (sub != null) {
			sub.onStop();
		}
		if (furnace != null && state != State.DONE) {
			ServerLevel l = SkillUtil.level(o);
			BlockState st = l.getBlockState(furnace);
			if (st.is(Blocks.FURNACE)) {
				setLit(l, st, false);
			}
		}
	}

	@Override
	String status() {
		return Recipes.label(smelt == null ? Items.AIR : smelt.out()) + "を焼いている(" + made + "/" + count + ")";
	}
}
