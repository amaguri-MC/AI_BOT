package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;

/**
 * ネザーゲートを本物の手順で作る（スピードランの「バケツ鋳造」）。
 * 枠（外寸4x5、角なし＝黒曜石10個）の1マスごとに:
 * 持ち物の黒曜石があればそれを置く。無ければ 空のバケツで溶岩を汲む → 枠の位置に流す → 水バケツの水をかけて黒曜石にする → 水を汲み戻す。
 * そのため 水バケツ1個＋空のバケツ1個 と、近くの溶岩だまりが要る。最後に火打石と打ち金で着火（PortalShape で本物のゲートを張る）。
 * 途中で溶岩が尽きたら、置けた所まで残して「できない」で終わる（次はその場所の続きから作る）。
 */
final class PortalSkill extends Skill {
	private enum Phase { WALK_SITE, CLEAR, FETCH, BRING, POUR, IGNITE, VERIFY }

	static final String MARK_SITE = "portal_site";
	static final String MARK_AXIS = "portal_axis";
	private static final int SCAN_R = 24;

	private Phase phase = Phase.WALK_SITE;
	private Direction.Axis axis;
	private BlockPos anchor; // 内側の左下
	private Vec3 stand;
	private final List<BlockPos> frame = new ArrayList<>();
	private final List<BlockPos> inner = new ArrayList<>();
	private final List<BlockPos> clearQueue = new ArrayList<>();
	private int idx;
	private BlockPos lava;
	private int delay;
	private int phaseTicks;
	private int placed;

	PortalSkill(FakeMember o, SkillRunner r) {
		super(o, r);
	}

	@Override
	String begin() {
		ServerLevel l = SkillUtil.level(o);
		if (l.dimension().equals(Level.END)) {
			return "できない: エンドではネザーゲートは作れない";
		}
		boolean nether = SkillUtil.isNether(o);
		int obsidian = Inv.count(o, Items.OBSIDIAN);
		boolean canCast = !nether && Inv.count(o, Items.WATER_BUCKET) > 0
				&& Inv.count(o, Items.BUCKET) + Inv.count(o, Items.LAVA_BUCKET) > 0;
		// 前回の途中の場所があれば続きから
		BlockPos site = runner.mark(MARK_SITE);
		BlockPos ax = runner.mark(MARK_AXIS);
		if (site != null && ax != null && site.distSqr(o.blockPosition()) < 48 * 48 && sameDimSite(l, site)) {
			axis = ax.getX() == 1 ? Direction.Axis.X : Direction.Axis.Z;
			anchor = site;
			layout();
			stand = standFor(l);
		} else if (obsidian >= 10) {
			Direction d = Direction.fromYRot(o.getYRot());
			anchor = o.blockPosition().relative(d, 3);
			axis = d.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
			layout();
			stand = Vec3.atBottomCenterOf(o.blockPosition().relative(d, 1));
		} else {
			if (!canCast) {
				if (nether) {
					return "できない: ネザーでは水が使えないので黒曜石が10個要る（今" + obsidian + "個）";
				}
				return "できない: 材料不足 水バケツ×1と空のバケツ×1（溶岩を汲んで黒曜石にする）";
			}
			BlockPos near = nearestLava(l, o.blockPosition());
			if (near == null) {
				return "できない: 近くに溶岩が見つからない";
			}
			if (!chooseSiteNear(l, near)) {
				return "できない: 溶岩のそばに枠を置ける場所が無い";
			}
		}
		int need = 0;
		for (BlockPos p : frame) {
			if (!l.getBlockState(p).is(Blocks.OBSIDIAN)) {
				need++;
			}
		}
		if (need > obsidian && !canCast) {
			return "できない: 材料不足 黒曜石×" + (need - obsidian) + "（または水バケツと空のバケツ）";
		}
		runner.setMark(MARK_SITE, anchor);
		runner.setMark(MARK_AXIS, new BlockPos(axis == Direction.Axis.X ? 1 : 0, 0, 0));
		for (BlockPos p : inner) {
			clearQueue.add(p);
		}
		for (BlockPos p : frame) {
			BlockState s = l.getBlockState(p);
			if (!s.is(Blocks.OBSIDIAN)) {
				clearQueue.add(p);
			}
		}
		nav.go(stand, 1.2, 1.0);
		nav.setSprint(true);
		return null;
	}

	private boolean sameDimSite(ServerLevel l, BlockPos site) {
		return l.hasChunkAt(site);
	}

	/** anchor と axis から 枠（置く順: 下辺→左柱→右柱→上辺）と内側を作る。 */
	private void layout() {
		frame.clear();
		inner.clear();
		Direction u = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		for (int a = 0; a < 2; a++) {
			frame.add(anchor.relative(u, a).below());
		}
		for (int b = 0; b < 3; b++) {
			frame.add(anchor.relative(u, -1).above(b));
		}
		for (int b = 0; b < 3; b++) {
			frame.add(anchor.relative(u, 2).above(b));
		}
		for (int a = 0; a < 2; a++) {
			frame.add(anchor.relative(u, a).above(3));
		}
		for (int a = 0; a < 2; a++) {
			for (int b = 0; b < 3; b++) {
				inner.add(anchor.relative(u, a).above(b));
			}
		}
	}

	/** 枠の正面2マス手前の立ち位置。 */
	private Vec3 standFor(ServerLevel l) {
		Direction n = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		for (Direction d : new Direction[] { n, n.getOpposite() }) {
			BlockPos p = anchor.relative(d, 2);
			if (standable(l, p)) {
				return Vec3.atBottomCenterOf(p);
			}
		}
		return Vec3.atBottomCenterOf(anchor.relative(n, 2));
	}

	private static boolean standable(ServerLevel l, BlockPos feet) {
		BlockState below = l.getBlockState(feet.below());
		return below.blocksMotion() && below.getFluidState().isEmpty()
				&& !l.getBlockState(feet).blocksMotion() && l.getBlockState(feet).getFluidState().isEmpty()
				&& !l.getBlockState(feet.above()).blocksMotion() && l.getBlockState(feet.above()).getFluidState().isEmpty();
	}

	private static boolean isLavaSource(BlockState s) {
		return s.is(Blocks.LAVA) && s.getFluidState().isSource();
	}

	/** 近い順に一番近い溶岩の源を探す（足元から半径24・下12〜上6）。 */
	static BlockPos nearestLava(ServerLevel l, BlockPos c) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
			for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
				if (!l.hasChunkAt(m.set(c.getX() + dx, c.getY(), c.getZ() + dz))) {
					continue;
				}
				for (int dy = -12; dy <= 6; dy++) {
					m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					if (isLavaSource(l.getBlockState(m))) {
						double d = m.distSqr(c);
						if (d < bd) {
							bd = d;
							best = m.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	/** 溶岩のそば（2〜4マス）に立てる場所を探し、そこから溶岩と反対側に枠を置く。 */
	private boolean chooseSiteNear(ServerLevel l, BlockPos lavaPos) {
		for (int r = 2; r <= 5; r++) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = 2; dy >= -2; dy--) {
					BlockPos s = lavaPos.relative(d, r).above(dy);
					if (!standable(l, s) || SkillUtil.nearLava(l, s)) {
						continue;
					}
					BlockPos a = s.relative(d, 2);
					Direction.Axis ax = d.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
					if (siteOk(l, a, ax)) {
						anchor = a;
						axis = ax;
						layout();
						stand = Vec3.atBottomCenterOf(s);
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 枠と内側に壊せないブロックや液体が無いか。 */
	private boolean siteOk(ServerLevel l, BlockPos a, Direction.Axis ax) {
		Direction.Axis keep = axis;
		BlockPos keepA = anchor;
		axis = ax;
		anchor = a;
		layout();
		boolean ok = true;
		for (BlockPos p : frame) {
			ok &= placeableOrBreakable(l, p);
		}
		for (BlockPos p : inner) {
			ok &= placeableOrBreakable(l, p);
		}
		axis = keep;
		anchor = keepA;
		return ok;
	}

	private static boolean placeableOrBreakable(ServerLevel l, BlockPos p) {
		BlockState s = l.getBlockState(p);
		if (!s.getFluidState().isEmpty()) {
			return false;
		}
		return s.isAir() || s.is(Blocks.OBSIDIAN) || s.getDestroySpeed(l, p) >= 0 && s.getDestroySpeed(l, p) < 5;
	}

	private ItemStack stackOf(Item item) {
		var c = o.getInventory();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack st = c.getItem(i);
			if (!st.isEmpty() && st.is(item)) {
				return st;
			}
		}
		return ItemStack.EMPTY;
	}

	private void swap(Item from, Item to) {
		Inv.remove(o, from, 1);
		Inv.add(o, new ItemStack(to));
		Inv.equip(o, stackOf(to));
	}

	@Override
	void tick() {
		ServerLevel l = SkillUtil.level(o);
		phaseTicks++;
		if (delay > 0) {
			delay--;
			return;
		}
		switch (phase) {
			case WALK_SITE -> {
				nav.tick();
				if (nav.arrived() || nav.failed() || phaseTicks > 400) {
					nav.stop();
					next(Phase.CLEAR);
				}
			}
			case CLEAR -> tickClear(l);
			case FETCH -> tickFetch(l);
			case BRING -> tickBring(l);
			case POUR -> tickPour(l);
			case IGNITE -> tickIgnite(l);
			case VERIFY -> tickVerify(l);
			default -> {
			}
		}
	}

	private void next(Phase p) {
		phase = p;
		phaseTicks = 0;
	}

	private void tickClear(ServerLevel l) {
		if (digger.active()) {
			int r = digger.tick();
			if (r == Digger.PROGRESS) {
				return;
			}
		}
		while (!clearQueue.isEmpty()) {
			BlockPos p = clearQueue.get(0);
			BlockState s = l.getBlockState(p);
			if (s.isAir() || s.is(Blocks.FIRE)) {
				clearQueue.remove(0);
				continue;
			}
			if (o.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) > 4.5 * 4.5) {
				nav.go(stand == null ? Vec3.atBottomCenterOf(p) : stand, 1.2, 1.0);
				nav.tick();
				if (phaseTicks > 600) {
					clearQueue.remove(0);
				}
				return;
			}
			if (!digger.begin(p)) {
				clearQueue.remove(0);
				continue;
			}
			return;
		}
		idx = 0;
		next(Phase.FETCH);
	}

	/** 次に埋める枠の位置へ。黒曜石を持っていれば置き、無ければ溶岩を汲みに行く。 */
	private void tickFetch(ServerLevel l) {
		while (idx < frame.size() && l.getBlockState(frame.get(idx)).is(Blocks.OBSIDIAN)) {
			idx++;
		}
		if (idx >= frame.size()) {
			next(Phase.IGNITE);
			return;
		}
		if (Inv.count(o, Items.OBSIDIAN) > 0 || Inv.count(o, Items.LAVA_BUCKET) > 0) {
			next(Phase.BRING);
			return;
		}
		if (Inv.count(o, Items.BUCKET) <= 0 || Inv.count(o, Items.WATER_BUCKET) <= 0) {
			fail("バケツが足りない（水バケツと空のバケツが要る）");
			return;
		}
		if (lava == null || !isLavaSource(l.getBlockState(lava))) {
			lava = nearestLava(l, BlockPos.containing(stand));
			if (lava == null) {
				fail("溶岩が足りない");
				return;
			}
		}
		Vec3 c = Vec3.atCenterOf(lava);
		if (o.getEyePosition().distanceToSqr(c) > 4.3 * 4.3) {
			nav.go(Vec3.atBottomCenterOf(lava.above()), 3.0, 1.0);
			nav.setVertTol(3.0);
			nav.tick();
			if (nav.failed() || phaseTicks > 300) {
				lava = null;
				phaseTicks = 0;
				fail("溶岩に近づけない");
			}
			return;
		}
		nav.stop();
		// 汲む
		SkillUtil.lookAt(o, c);
		Inv.equip(o, stackOf(Items.BUCKET));
		SkillUtil.swing(o);
		l.setBlock(lava, Blocks.AIR.defaultBlockState(), 3);
		l.playSound(null, lava, SoundEvents.BUCKET_FILL_LAVA, SoundSource.PLAYERS, 1.0f, 1.0f);
		swap(Items.BUCKET, Items.LAVA_BUCKET);
		lava = null;
		delay = 4;
		next(Phase.BRING);
	}

	private void tickBring(ServerLevel l) {
		BlockPos p = frame.get(idx);
		Vec3 c = Vec3.atCenterOf(p);
		if (o.getEyePosition().distanceToSqr(c) > 4.3 * 4.3) {
			nav.go(stand, 1.2, 1.0);
			nav.tick();
			if (phaseTicks > 300) {
				fail("枠の場所に戻れない");
			}
			return;
		}
		nav.stop();
		SkillUtil.lookAt(o, c);
		BlockState here = l.getBlockState(p);
		if (!here.isAir() && !here.is(Blocks.FIRE) && here.getFluidState().isEmpty()) {
			// まだ何かある（流れてきた物など）→ 掘る
			clearQueue.add(0, p);
			next(Phase.CLEAR);
			return;
		}
		if (Inv.count(o, Items.OBSIDIAN) > 0) {
			Inv.equip(o, stackOf(Items.OBSIDIAN));
			SkillUtil.swing(o);
			Inv.remove(o, Items.OBSIDIAN, 1);
			BlockState ob = Blocks.OBSIDIAN.defaultBlockState();
			l.setBlock(p, ob, 3);
			l.playSound(null, p, ob.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.9f);
			placed++;
			idx++;
			delay = 4;
			next(Phase.FETCH);
			return;
		}
		// 溶岩を流す
		Inv.equip(o, stackOf(Items.LAVA_BUCKET));
		SkillUtil.swing(o);
		l.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
		l.playSound(null, p, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.PLAYERS, 1.0f, 1.0f);
		swap(Items.LAVA_BUCKET, Items.BUCKET);
		delay = 5;
		next(Phase.POUR);
	}

	/** 水をかけて黒曜石にし、水を汲み戻す（水は持ち物の水バケツのまま）。 */
	private void tickPour(ServerLevel l) {
		BlockPos p = frame.get(idx);
		SkillUtil.lookAt(o, Vec3.atCenterOf(p.above()));
		Inv.equip(o, stackOf(Items.WATER_BUCKET));
		SkillUtil.swing(o);
		l.playSound(null, p, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 1.0f, 1.0f);
		if (l.getBlockState(p).is(Blocks.LAVA)) {
			l.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), 3);
			l.levelEvent(LevelEvent.LAVA_FIZZ, p, 0);
			l.playSound(null, p, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.6f);
			l.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.01);
			placed++;
		}
		l.playSound(null, p, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8f, 1.0f);
		idx++;
		delay = 4;
		next(Phase.FETCH);
	}

	private void tickIgnite(ServerLevel l) {
		BlockPos p = inner.get(0);
		Vec3 c = Vec3.atCenterOf(p);
		if (o.getEyePosition().distanceToSqr(c) > 4.3 * 4.3) {
			nav.go(stand, 1.2, 1.0);
			nav.tick();
			if (phaseTicks < 300) {
				return;
			}
		}
		nav.stop();
		SkillUtil.lookAt(o, c);
		SkillUtil.swing(o);
		ItemStack fns = stackOf(Items.FLINT_AND_STEEL);
		if (!fns.isEmpty()) {
			Inv.equip(o, fns);
			l.playSound(null, p, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
			Inv.damageTool(o, fns, 1);
		} else if (Inv.count(o, Items.FIRE_CHARGE) > 0) {
			Inv.equip(o, stackOf(Items.FIRE_CHARGE));
			Inv.remove(o, Items.FIRE_CHARGE, 1);
			l.playSound(null, p, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
		} else {
			// 火打石が無いときは溶岩の熱で火を付けた扱い（スピードランの「溶岩着火」の簡略）。
			l.playSound(null, p, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.0f, 1.0f);
		}
		ignite(l, p, axis);
		delay = 10;
		next(Phase.VERIFY);
	}

	/** 内側の1マスから本物のゲートを張る。枠が認識されなければ内側を直接ゲートで埋める。 */
	static void ignite(ServerLevel l, BlockPos innerPos, Direction.Axis axis) {
		Optional<PortalShape> shape = PortalShape.findEmptyPortalShape(l, innerPos, axis);
		if (shape.isPresent()) {
			shape.get().createPortalBlocks(l);
		} else {
			l.setBlock(innerPos, Blocks.FIRE.defaultBlockState(), 3);
		}
	}

	private void tickVerify(ServerLevel l) {
		if (!l.getBlockState(inner.get(0)).is(Blocks.NETHER_PORTAL)) {
			BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
			for (BlockPos p : inner) {
				l.setBlock(p, portal, 18);
			}
		}
		BlockPos p = inner.get(0);
		runner.setMark(SkillUtil.isNether(o) ? RunSkills.MARK_PORTAL_NETHER : RunSkills.MARK_PORTAL_OW, p);
		runner.setMark(MARK_SITE, null);
		runner.setMark(MARK_AXIS, null);
		String where = SkillUtil.pos(p);
		finish("ネザーゲートを作った " + where, "ゲート作った 座標 " + where);
	}

	private void fail(String why) {
		finish("できない: " + why + "（黒曜石 " + countFrame() + "/10）", null);
	}

	private int countFrame() {
		ServerLevel l = SkillUtil.level(o);
		int n = 0;
		for (BlockPos p : frame) {
			if (l.getBlockState(p).is(Blocks.OBSIDIAN)) {
				n++;
			}
		}
		return n;
	}

	/** 早送り用: 足元の前に黒曜石の枠を一気に組んで着火する。内側の左下を返す。 */
	static BlockPos buildInstant(ServerLevel l, BlockPos feet, Direction facing) {
		BlockPos a = feet.relative(facing, 2);
		Direction.Axis ax = facing.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		Direction u = ax == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		for (int i = -1; i <= 2; i++) {
			for (int b = -1; b <= 3; b++) {
				BlockPos p = a.relative(u, i).above(b);
				boolean corner = (i == -1 || i == 2) && (b == -1 || b == 3);
				boolean isFrame = i == -1 || i == 2 || b == -1 || b == 3;
				if (corner) {
					continue;
				}
				l.setBlock(p, isFrame ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
		}
		ignite(l, a, ax);
		if (!l.getBlockState(a).is(Blocks.NETHER_PORTAL)) {
			BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, ax);
			for (int i = 0; i <= 1; i++) {
				for (int b = 0; b <= 2; b++) {
					l.setBlock(a.relative(u, i).above(b), portal, 18);
				}
			}
		}
		return a;
	}

	@Override
	String status() {
		return switch (phase) {
			case WALK_SITE -> "ゲートを作る場所へ移動中";
			case CLEAR -> "ゲートの場所を片付けている";
			case FETCH -> "溶岩を汲みに行っている（黒曜石" + countFrame() + "/10）";
			case BRING, POUR -> "溶岩に水をかけて黒曜石を作っている（" + countFrame() + "/10）";
			default -> "ゲートに火を付けている";
		};
	}
}
