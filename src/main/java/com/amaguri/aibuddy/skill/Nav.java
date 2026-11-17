package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 経路移動。owner.getNavigation() に数十ブロックずつ区切って渡す。
 * 3ブロック以上はダッシュ、平らな直線ではダッシュジャンプ（Motion.Dasher）。
 * 経路の再計算は最短でも1秒（20tick）に1回。
 * 5秒進まなければ 掘って進む → 柱 → 橋 → 回り込み の順で打開する。溶岩は避ける。
 */
final class Nav {
	private enum Mode { NORMAL, DETOUR, DIG, PILLAR, BRIDGE }

	/** これ以上離れていればダッシュ（ブロック） */
	static final double DASH_DISTANCE = 3.0;
	static final int REPATH_MIN_TICKS = 20;


	private final FakeMember o;
	private final Digger digger;

	private Vec3 goal;
	private double reach = 1.5;
	private double speed = 1.0;
	private double vertTol = 3.0;
	private boolean sprint;

	private Mode mode = Mode.NORMAL;
	private int modeTicks;
	private int checkTimer;
	private int noProgress;
	private int stage;
	private int rescues;
	private int stableChecks;
	private Vec3 lastCheck;
	private boolean pathFailed;
	private boolean failed;
	private boolean detourSign;
	private final Motion.Dasher dasher;
	private int sinceRepath = REPATH_MIN_TICKS;
	private Vec3 pathTarget;
	private int directTicks;

	// DIG
	private final List<BlockPos> digQueue = new ArrayList<>();
	private Vec3 stepTarget;
	private int walkTicks;
	private int digSteps;

	// PILLAR
	private final Motion.Pillar pillar = new Motion.Pillar();
	private int pillars;

	// BRIDGE
	private int bridged;


	Nav(FakeMember o, Digger digger) {
		this.o = o;
		this.digger = digger;
		this.dasher = new Motion.Dasher(o);
	}

	void go(Vec3 g, double reach, double speed) {
		if (goal == null || goal.distanceToSqr(g) > 9.0) {
			stage = 0;
			rescues = 0;
			noProgress = 0;
			failed = false;
			mode = Mode.NORMAL;
			pathTarget = null;
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
		failed = false;
		rescues = 0;
		mode = Mode.NORMAL;
		digQueue.clear();
		digger.cancel();
		o.getNavigation().stop();
		o.setSprinting(false);
		dasher.reset();
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
		if (o.isInWater() || o.isInLava()) {
			o.getJumpControl().jump(); // 泳いで上がる
		}
		switch (mode) {
			case NORMAL -> tickNormal();
			case DETOUR -> tickDetour();
			case DIG -> tickDig();
			case PILLAR -> tickPillar();
			case BRIDGE -> tickBridge();
		}
	}

	// ---- NORMAL ----

	/**
	 * 遠い目標は32ブロック先の区間に区切る。高さは地表に吸着するのを基本にし（山・谷でも足場がある所）、
	 * そこへ到達できない（上空の足場・屋根の上・洞窟など地表が使えない）ときだけ目標への線形補間にする。
	 * 到達判定の createPath は重いので、区間を出し直すときだけ呼ぶ（毎tickは呼ばない）。
	 */
	private Vec3 segmentTarget() {
		Vec3 pos = o.position();
		double dx = goal.x - pos.x;
		double dz = goal.z - pos.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h > 36) {
			double k = 32.0 / h;
			double x = pos.x + dx * k;
			double z = pos.z + dz * k;
			Vec3 snapped = SkillUtil.snap(o, x, z, pos.y);
			if (reachable(snapped)) {
				return snapped;
			}
			Vec3 lerp = new Vec3(x, pos.y + (goal.y - pos.y) * k, z);
			if (reachable(lerp)) {
				return lerp;
			}
			return snapped; // どちらも部分経路になるなら従来どおり（詰まれば rescue が掘る）
		}
		return goal;
	}

	private boolean reachable(Vec3 t) {
		Path p = o.getNavigation().createPath(BlockPos.containing(t.x, t.y, t.z), 1);
		return p != null && p.canReach();
	}

	/** 目標への直線上の点（狭い範囲）。直進の最終手段で使う。 */
	private Vec3 segmentTarget0() {
		Vec3 pos = o.position();
		double dx = goal.x - pos.x;
		double dz = goal.z - pos.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h < 8) {
			return goal;
		}
		double k = 8.0 / h;
		return new Vec3(pos.x + dx * k, pos.y + (goal.y - pos.y) * k, pos.z + dz * k);
	}

	/** 経路の終点が今いる所から1.5ブロック以内（長さ0の部分経路）なら「進めない」とみなす。 */
	private boolean usefulPath(PathNavigation nav) {
		Path p = nav.getPath();
		if (p == null || p.getNodeCount() == 0) {
			return false;
		}
		BlockPos end = p.getNodePos(p.getNodeCount() - 1);
		Vec3 e = Vec3.atBottomCenterOf(end);
		return SkillUtil.horiz(e, o.position()) > 1.5 || Math.abs(e.y - o.getY()) > 2.0;
	}

	/**
	 * 区間を出す。同じtickで 区間目標 → 補間点 → ゴール直指定 → 半分の距離 の順に試し、進める経路が取れた所で止める。
	 * 全部だめで、ゴール方向が平らで空いていれば 20tick だけ moveControl で直進する（最終手段）。
	 */
	private void issueSegment(PathNavigation nav, double mod) {
		Vec3 pos = o.position();
		Vec3 seg = segmentTarget();
		double dx = goal.x - pos.x;
		double dy = goal.y - pos.y;
		double dz = goal.z - pos.z;
		Vec3[] tries = {
				seg,
				new Vec3(pos.x + dx * 0.5, pos.y + dy * 0.5, pos.z + dz * 0.5),
				goal,
				new Vec3(pos.x + dx * 0.25, pos.y + dy * 0.25, pos.z + dz * 0.25) };
		for (Vec3 t : tries) {
			boolean ok = nav.moveTo(t.x, t.y, t.z, mod);
			if (usefulPath(nav)) {
				pathFailed = !ok;
				pathTarget = t;
				return;
			}
		}
		pathFailed = true;
		pathTarget = seg;
		nav.stop();
		if (Math.abs(dy) < 2.5 && flatAhead(dx, dz)) {
			directTicks = 10;
		}
	}

	private boolean flatAhead(double dx, double dz) {
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h < 0.5) {
			return false;
		}
		ServerLevel l = SkillUtil.level(o);
		for (int k = 1; k <= 7; k++) {
			BlockPos c = BlockPos.containing(o.getX() + dx / h * k, o.getY(), o.getZ() + dz / h * k);
			if (l.getBlockState(c).blocksMotion() || l.getBlockState(c.above()).blocksMotion()
					|| !l.getBlockState(c.below()).blocksMotion() || Motion.isDeadly(l.getBlockState(c.below()))) {
				return false;
			}
		}
		return true;
	}

	/** 1〜2ブロック先に立てる床があるか（落差2以内・溶岩でない）。 */
	private boolean flatNear(double dx, double dz) {
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h < 0.3) {
			return true;
		}
		ServerLevel l = SkillUtil.level(o);
		for (int k = 1; k <= 2; k++) {
			BlockPos c = BlockPos.containing(o.getX() + dx / h * k, o.getY(), o.getZ() + dz / h * k);
			boolean floor = false;
			for (int d = 1; d <= 3; d++) {
				var st = l.getBlockState(c.below(d));
				if (Motion.isDeadly(st)) {
					return false;
				}
				if (st.blocksMotion()) {
					floor = true;
					break;
				}
			}
			if (!floor) {
				return false;
			}
		}
		return true;
	}

	private void tickNormal() {
		// UNVERIFIED: 固体ブロックを目標にした moveTo は部分経路になる想定（canReach=false でも歩く）。詰まれば rescue() が掘る。
		PathNavigation nav = o.getNavigation();
		sinceRepath++;
		boolean dash = sprint || distance() >= DASH_DISTANCE;
		double mod = Motion.speedMod(o, dash, speed);
		// 経路が終わっている（区間の継ぎ目・開始直後・失敗）なら同じtickで次を出す（2tickの暴走ガードだけ）。
		// 「1秒に1回まで」は進んでいる最中の再計算（目標の移動・100tick経過）にだけ適用する。
		boolean ended = nav.isDone() || pathTarget == null;
		boolean wantNew = ended ? sinceRepath >= (pathFailed ? REPATH_MIN_TICKS : 2) : sinceRepath >= 100; // 失敗が続くときだけ1秒に1回
		if (directTicks > 0) {
			directTicks--;
			Vec3 gt = segmentTarget0();
			// 直進中も毎tick 1〜2ブロック先の床を見直し、崖（落差2超）・溶岩なら即やめる
			if (!flatNear(gt.x - o.getX(), gt.z - o.getZ())) {
				directTicks = 0;
				o.getMoveControl().setWantedPosition(o.getX(), o.getY(), o.getZ(), 0);
			}
			o.getMoveControl().setWantedPosition(gt.x, gt.y, gt.z, mod);
			if (o.onGround() && gt.y > o.getY() + 0.6) {
				o.getJumpControl().jump();
			}
			o.setSprinting(dash);
			sinceRepath = REPATH_MIN_TICKS;
		} else if (wantNew) {
			issueSegment(nav, mod);
			sinceRepath = 0;
		}
		nav.setSpeedModifier(mod);
		dasher.tick(dash);
		if (o.tickCount % 3 == 0 && Motion.lavaBrake(o)) {
			noProgress++;
			sinceRepath = REPATH_MIN_TICKS;
		}
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
		// 5秒詰まったら 掘って進む → 柱 → 橋 → 回り込み の順
		int s = stage++ % 4;
		switch (s) {
			case 0 -> startDig();
			case 1 -> startPillar();
			case 2 -> startBridge();
			default -> startDetour();
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
		o.setShiftKeyDown(false);
		mode = Mode.NORMAL;
		sinceRepath = REPATH_MIN_TICKS;
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
		pillar.reset();
		if (!Motion.hasScaffold(o)) {
			backToNormal();
		}
	}

	private void tickPillar() {
		if (++modeTicks > 240) {
			backToNormal();
			return;
		}
		int r = pillar.tick(o, digger);
		if (r == Motion.PILLAR_NO_BLOCK || r == Motion.PILLAR_BLOCKED) {
			backToNormal();
		} else if (r == Motion.PILLAR_STEP) {
			pillars++;
			if (goal.y - o.getY() < 1.5 || pillars >= 12 || pathExists()) {
				backToNormal();
			}
		}
	}

	// ---- BRIDGE ----

	private void startBridge() {
		mode = Mode.BRIDGE;
		modeTicks = 0;
		bridged = 0;
		if (!Motion.hasScaffold(o)) {
			backToNormal();
		}
	}

	/** 床が無い所に1個ずつ置いて渡る（置いてから進むので落ちない）。 */
	private void tickBridge() {
		if (++modeTicks > 400 || bridged >= 24) {
			backToNormal();
			return;
		}
		ServerLevel l = SkillUtil.level(o);
		Direction d = SkillUtil.horizontalToward(o.position(), goal);
		BlockPos feetFloor = o.blockPosition().below();
		BlockPos nextFloor = feetFloor.relative(d);
		o.setShiftKeyDown(true);
		SkillUtil.lookAt(o, Vec3.atCenterOf(nextFloor));
		if (l.getBlockState(nextFloor.above()).blocksMotion() || l.getBlockState(nextFloor.above(2)).blocksMotion()) {
			o.setShiftKeyDown(false);
			backToNormal(); // 壁: 次は別の手段
			return;
		}
		if (!l.getBlockState(nextFloor).blocksMotion()) {
			if (!Motion.placeScaffold(o, nextFloor)) {
				o.setShiftKeyDown(false);
				backToNormal();
			} else {
				bridged++;
			}
			return;
		}
		Vec3 c = Vec3.atBottomCenterOf(nextFloor.above());
		o.getMoveControl().setWantedPosition(c.x, c.y, c.z, Motion.speedMod(o, false, 1.0));
		if (modeTicks % 10 == 0 && pathExists()) {
			o.setShiftKeyDown(false);
			backToNormal();
		}
	}
}
