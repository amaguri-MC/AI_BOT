package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 経路移動。owner.getNavigation() に数十ブロックずつ区切って渡す。
 * 5秒進まなければ 回り込み → 掘る / 足場（ピラー）を置く の順で打開する。
 */
final class Nav {
	private enum Mode { NORMAL, DETOUR, DIG, PILLAR }


	private final FakeMember o;
	private final Digger digger;

	private Vec3 goal;
	private double reach = 1.5;
	private double speed = 1.0;
	private double vertTol = 3.0;
	private boolean sprint;

	private Mode mode = Mode.NORMAL;
	private int modeTicks;
	private int repath;
	private int checkTimer;
	private int noProgress;
	private int stage;
	private int rescues;
	private int stableChecks;
	private Vec3 lastCheck;
	private boolean pathFailed;
	private boolean failed;
	private boolean detourSign;

	// DIG
	private final List<BlockPos> digQueue = new ArrayList<>();
	private Vec3 stepTarget;
	private int walkTicks;
	private int digSteps;

	// PILLAR
	private BlockPos pillarCell;
	private int pillars;


	Nav(FakeMember o, Digger digger) {
		this.o = o;
		this.digger = digger;
	}

	void go(Vec3 g, double reach, double speed) {
		if (goal == null || goal.distanceToSqr(g) > 9.0) {
			stage = 0;
			rescues = 0;
			noProgress = 0;
			failed = false;
			mode = Mode.NORMAL;
			repath = 0;
		}
		this.goal = g;
		this.reach = reach;
		this.speed = speed;
	}

	void go(Vec3 g, double reach) {
		go(g, reach, 1.0);
	}

	void setVertTol(double v) {
		this.vertTol = v;
	}

	void stop() {
		goal = null;
		mode = Mode.NORMAL;
		digQueue.clear();
		digger.cancel();
		o.getNavigation().stop();
		o.setSprinting(false);
	}

	boolean failed() {
		return failed;
	}

	boolean hasGoal() {
		return goal != null;
	}

	Vec3 goal() {
		return goal;
	}

	boolean arrived() {
		if (goal == null) {
			return false;
		}
		Vec3 p = o.position();
		return SkillUtil.horiz(p, goal) <= reach && Math.abs(goal.y - p.y) <= Math.max(reach, vertTol);
	}

	double distance() {
		return goal == null ? 0 : SkillUtil.horiz(o.position(), goal);
	}

	void tick() {
		if (goal == null || failed) {
			return;
		}
		if (arrived()) {
			o.getNavigation().stop();
			o.setSprinting(false);
			return;
		}
		switch (mode) {
			case NORMAL -> tickNormal();
			case DETOUR -> tickDetour();
			case DIG -> tickDig();
			case PILLAR -> tickPillar();
		}
	}

	// ---- NORMAL ----

	private Vec3 segmentTarget() {
		Vec3 pos = o.position();
		double dx = goal.x - pos.x;
		double dz = goal.z - pos.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h > 36) {
			double k = 32.0 / h;
			return SkillUtil.snap(o, pos.x + dx * k, pos.z + dz * k, pos.y);
		}
		return goal;
	}

	private void tickNormal() {
		// UNVERIFIED: 固体ブロックを目標にした moveTo は部分経路になる想定（canReach=false でも歩く）。詰まれば rescue() が掘る。
		PathNavigation nav = o.getNavigation();
		repath--;
		if (repath <= 0 || nav.isDone()) {
			Vec3 t = segmentTarget();
			boolean ok = nav.moveTo(t.x, t.y, t.z, speed);
			pathFailed = !ok;
			repath = 20;
		}
		o.setSprinting(sprint || distance() > 14);
		if (++checkTimer < 20) {
			return;
		}
		checkTimer = 0;
		Vec3 p = o.position();
		if (lastCheck != null && p.distanceToSqr(lastCheck) < 0.25) {
			noProgress++;
		} else {
			noProgress = 0;
			if (++stableChecks > 6) {
				stage = 0;
			}
		}
		lastCheck = p;
		if (noProgress >= (pathFailed ? 3 : 5)) {
			rescue();
		}
	}

	void setSprint(boolean s) {
		this.sprint = s;
	}

	private void rescue() {
		noProgress = 0;
		stableChecks = 0;
		if (++rescues > 30) {
			failed = true;
			o.getNavigation().stop();
			return;
		}
		double dy = goal.y - o.getY();
		boolean wantUp = dy >= 2.0 && SkillUtil.horiz(o.position(), goal) < 10;
		int s = stage++;
		if (s % 2 == 0) {
			startDetour();
		} else if (wantUp) {
			startPillar();
		} else {
			startDig();
		}
	}

	// ---- DETOUR ----

	private void startDetour() {
		mode = Mode.DETOUR;
		modeTicks = 0;
		detourSign = !detourSign;
		Vec3 pos = o.position();
		double dx = goal.x - pos.x;
		double dz = goal.z - pos.z;
		double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
		double ux = dx / len;
		double uz = dz / len;
		double sgn = detourSign ? 1 : -1;
		double tx = pos.x + (-uz) * 5 * sgn + ux * 3;
		double tz = pos.z + ux * 5 * sgn + uz * 3;
		Vec3 t = SkillUtil.snap(o, tx, tz, pos.y);
		o.getNavigation().moveTo(t.x, t.y, t.z, speed);
	}

	private void tickDetour() {
		if (++modeTicks > 60 || o.getNavigation().isDone()) {
			backToNormal();
		}
	}

	private void backToNormal() {
		mode = Mode.NORMAL;
		repath = 0;
		lastCheck = null;
		checkTimer = 0;
		noProgress = 0;
	}

	// ---- DIG ----

	private void startDig() {
		mode = Mode.DIG;
		digSteps = 0;
		prepareDigStep();
	}

	private boolean solidish(BlockPos p) {
		return SkillUtil.level(o).getBlockState(p).blocksMotion();
	}

	private void prepareDigStep() {
		ServerLevel l = SkillUtil.level(o);
		digQueue.clear();
		walkTicks = 0;
		Vec3 pos = o.position();
		Direction d = SkillUtil.horizontalToward(pos, goal);
		if (SkillUtil.horiz(pos, goal) < 1.5) {
			d = Direction.fromYRot(o.getYRot());
		}
		BlockPos feet = o.blockPosition();
		BlockPos front = feet.relative(d);
		double dy = goal.y - o.getY();
		List<BlockPos> cells = new ArrayList<>();
		BlockPos stand;
		if (dy >= 2.0) {
			// 階段状に掘り上がる
			cells.add(feet.above(2));
			cells.add(front.above());
			cells.add(front.above(2));
			stand = front.above();
		} else if (dy <= -2.0) {
			cells.add(front.above());
			cells.add(front);
			cells.add(front.below());
			stand = front.below();
		} else {
			cells.add(front.above());
			cells.add(front);
			stand = front;
		}
		for (BlockPos c : cells) {
			BlockState s = l.getBlockState(c);
			if (!SkillUtil.isLiquidBlock(s) && !SkillUtil.nearLava(l, c)) {
				if (s.blocksMotion()) {
					digQueue.add(c);
				}
			} else {
				// 水・溶岩に当たる掘削はしない
				startDetour();
				return;
			}
		}
		stepTarget = Vec3.atBottomCenterOf(stand);
	}

	private void tickDig() {
		if (!digQueue.isEmpty()) {
			BlockPos t = digQueue.get(0);
			if (!digger.active() || !digger.pos().equals(t)) {
				if (!digger.begin(t)) {
					digQueue.remove(0);
					return;
				}
			}
			int r = digger.tick();
			if (r != Digger.PROGRESS) {
				digQueue.remove(0);
			}
			return;
		}
		walkTicks++;
		o.getMoveControl().setWantedPosition(stepTarget.x, stepTarget.y, stepTarget.z, 1.0);
		if (stepTarget.y > o.getY() + 0.6 && o.onGround()) {
			o.getJumpControl().jump();
		}
		Vec3 p = o.position();
		boolean there = SkillUtil.horiz(p, stepTarget) < 0.5 && Math.abs(p.y - stepTarget.y) < 1.0;
		if (there || walkTicks > 25) {
			digSteps++;
			if (pathExists() || digSteps >= 8) {
				backToNormal();
			} else {
				prepareDigStep();
			}
		}
	}

	private boolean pathExists() {
		Vec3 t = segmentTarget();
		Path path = o.getNavigation().createPath(BlockPos.containing(t.x, t.y, t.z), 1);
		return path != null && path.canReach();
	}

	// ---- PILLAR ----

	private void startPillar() {
		mode = Mode.PILLAR;
		modeTicks = 0;
		pillars = 0;
		pillarCell = null;
	}

	private void tickPillar() {
		ServerLevel l = SkillUtil.level(o);
		if (++modeTicks > 240) {
			backToNormal();
			return;
		}
		SkillUtil.lookAt(o, o.position().add(0, -1, 0));
		if (o.onGround()) {
			if (pillarCell != null) {
				pillars++;
				pillarCell = null;
				if (goal.y - o.getY() < 1.5 || pillars >= 12 || pathExists()) {
					backToNormal();
					return;
				}
			}
			BlockPos head2 = o.blockPosition().above(2);
			if (l.getBlockState(head2).blocksMotion()) {
				if (!digger.active()) {
					if (!digger.begin(head2)) {
						backToNormal();
						return;
					}
				}
				digger.tick();
				return;
			}
			pillarCell = o.blockPosition();
			o.getJumpControl().jump();
		} else if (pillarCell != null && o.getY() >= pillarCell.getY() + 1.0
				&& !l.getBlockState(pillarCell).blocksMotion()) {
			placeScaffold(pillarCell);
		}
	}

	private void placeScaffold(BlockPos p) {
		ServerLevel l = SkillUtil.level(o);
		net.minecraft.world.item.Item[] mats = { Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.STONE,
				Items.COBBLED_DEEPSLATE };
		BlockState state = Blocks.COBBLESTONE.defaultBlockState();
		for (net.minecraft.world.item.Item m : mats) {
			if (o.getInventory().countItem(m) > 0) {
				ItemStack taken = o.getInventory().removeItemType(m, 1);
				if (!taken.isEmpty() && taken.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
					state = bi.getBlock().defaultBlockState();
				}
				break;
			}
		}
		// 手持ちが無くても足場は置く（演出優先）
		l.setBlock(p, state, 3);
		l.playSound(null, p, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
		SkillUtil.swing(o);
	}
}
