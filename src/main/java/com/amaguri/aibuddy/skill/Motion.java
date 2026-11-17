package com.amaguri.aibuddy.skill;

import java.util.Map;
import java.util.WeakHashMap;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 動きの共通部品: 速度の式、ダッシュ、ダッシュジャンプ、溶岩ブレーキ、水バケツ着地、足場置き、柱積み。
 *
 * <h2>速度の式（26.2 の MoveControl / LivingEntity.travel の読み）</h2>
 * apidump は javap のシグネチャだけで本体が読めないため、式は次のメンバーの存在と
 * バニラ既知の挙動から組み立てた（UNVERIFIED: 実測は gametest と calibrate で補正する）。
 * <ul>
 * <li>MoveControl.tick: {@code mob.setSpeed(speedModifier * MOVEMENT_SPEED)}。Mob.setSpeed(float) が存在し、
 *     Mob 版は {@code zza} も同じ値にする（Mob#setZza / Mob#setSpeed が apidump にある）。</li>
 * <li>LivingEntity.travel（地上）: 摩擦 f = ブロック摩擦0.6 * 0.91 = 0.546。
 *     加速度 a = zza * 0.98 * getSpeed() * (0.216 / 0.6^3 = 1.0)。プレイヤーは zza=1 なので a = 0.098（歩き）。
 *     mob は zza = getSpeed() なので <b>a = 0.98 * s^2</b>（s = speedModifier * 属性値）。二乗になる。</li>
 * <li>定常: 1tick の移動量 D = a / (1 - 0.546) = 2.203 a。プレイヤー歩き a=0.098 → 0.2159/tick = 4.317 b/s（既知値と一致）。</li>
 * <li>ダッシュ中は LivingEntity.setSprinting が MOVEMENT_SPEED に +30%（ADD_MULTIPLIED_TOTAL）を足す。</li>
 * <li>空中: Mob の flyingSpeed は 0.02 固定で、mob は zza(=s) 倍されるため空中加速が約0.007しかない。
 *     プレイヤーのダッシュジャンプは 0.02548（flyingSpeed 0.026 * 0.98）。
 *     差分 AIR_EXTRA を自前で足して同じ軌道にする（足さないと mob のジャンプ連打は 3.3 b/s に落ちる）。</li>
 * </ul>
 * 目標: 歩き4.3 / ダッシュ5.6 / ダッシュジャンプ約7.0 ブロック/秒。
 * 検算（/tmp のシミュレータ: 摩擦0.546・空中0.91・重力0.08・ジャンプ0.42・ダッシュ踏切+0.2）:
 * 地上 a=0.1271 → 5.60 b/s、空中加速 0.0255 付きのジャンプ連打 → 7.05 b/s（プレイヤーの 7.05 と一致）。
 */
final class Motion {
	private Motion() {
	}

	/** FakeMember.createAttributes の MOVEMENT_SPEED と同じ値。 */
	static final double BASE_SPEED = 0.25;
	static final double WALK_BPS = 4.3;
	static final double DASH_BPS = 5.6;
	static final double DASHJUMP_BPS = 7.0;
	/** ダッシュ中のジャンプ間隔（tick）。着地まで12tickかかるので実際は着地した次のtick。 */
	static final int JUMP_INTERVAL = 11;

	private static final double SPRINT_MULT = 1.3;
	private static final double INPUT_DAMP = 0.98;
	private static final double GROUND_FRICTION = 0.6 * 0.91;
	/** プレイヤーの空中加速(0.0255) - mob が自力で得る空中加速(0.02 * 0.35) */
	private static final double AIR_EXTRA = 0.0184;
	private static final int FLAT_NODES = 5;

	private static final Map<FakeMember, double[]> CALIB = new WeakHashMap<>();

	/** 目標の blocks/s から speedModifier を逆算する。 */
	private static double modFor(double bps, boolean sprint) {
		double d = bps / 20.0;
		double a = d * (1.0 - GROUND_FRICTION);
		double s = Math.sqrt(a / INPUT_DAMP);
		return s / (BASE_SPEED * (sprint ? SPRINT_MULT : 1.0));
	}

	private static double calib(FakeMember o) {
		double[] c = CALIB.get(o);
		return c == null ? 1.0 : c[0];
	}

	/**
	 * navigation.moveTo / setSpeedModifier に渡す倍率。
	 * rel は呼び出し側の相対倍率で、1.0 を上限に「線形に遅くする」意味だけ残す（1.0超は無視）。
	 */
	static double speedMod(FakeMember o, boolean sprint, double rel) {
		double r = Math.min(1.0, Math.max(0.2, rel));
		return modFor(sprint ? DASH_BPS : WALK_BPS, sprint) * calib(o) * Math.sqrt(r);
	}

	// ---- 向き ----

	static Vec3 dirOfYaw(float yRot) {
		float r = yRot * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
	}

	/** 体・頭・視線を一瞬で p に向ける。ストレイフや後退で向きを固定したいとき用。 */
	static void face(FakeMember o, Vec3 p) {
		double dx = p.x - o.getX();
		double dz = p.z - o.getZ();
		double dy = p.y - o.getEyeY();
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f);
		float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		o.setYRot(yaw);
		o.setXRot(pitch);
		o.setYHeadRot(yaw);
		o.setYBodyRot(yaw);
	}

	// ---- ダッシュ / ダッシュジャンプ ----

	/** Nav が1つずつ持つ。毎tick tick(sprint) を呼ぶ。 */
	static final class Dasher {
		private final FakeMember o;
		private int jumpCd;
		private boolean airDash;
		private int winTicks;
		private int winJumps;
		private boolean winClean = true;
		private Vec3 winStart;

		Dasher(FakeMember o) {
			this.o = o;
		}

		void reset() {
			airDash = false;
			winTicks = 0;
			winJumps = 0;
			winClean = true;
			winStart = null;
		}

		void tick(boolean sprint) {
			o.setSprinting(sprint);
			if (jumpCd > 0) {
				jumpCd--;
			}
			boolean hasPath = o.getNavigation().getPath() != null && !o.getNavigation().isDone();
			if (!sprint || !hasPath || o.isInWater() || o.isInLava() || o.getVehicle() != null) {
				winClean = false;
				airDash = false;
			} else if (o.onGround()) {
				airDash = false;
				if (jumpCd <= 0 && straightFlat() && safeToJump(o)) {
					o.getJumpControl().jump();
					jumpCd = JUMP_INTERVAL;
					airDash = true;
					winJumps++;
				}
			} else if (airDash) {
				Vec3 h = dirOfYaw(o.getYRot());
				o.addDeltaMovement(new Vec3(h.x * AIR_EXTRA, 0, h.z * AIR_EXTRA));
			}
			calibrate();
		}

		/** 次の5ノードが同じ高さで一直線、頭上が空いていて足元に危険物が無い。 */
		private boolean straightFlat() {
			Path p = o.getNavigation().getPath();
			if (p == null) {
				return false;
			}
			int i = p.getNextNodeIndex();
			if (p.getNodeCount() - i < FLAT_NODES) {
				return false;
			}
			ServerLevel l = SkillUtil.level(o);
			BlockPos feet = o.blockPosition();
			BlockPos n0 = p.getNodePos(i);
			BlockPos nEnd = p.getNodePos(i + FLAT_NODES - 1);
			if (n0.getY() != feet.getY() || nEnd.getY() != feet.getY()) {
				return false;
			}
			double dx = nEnd.getX() - feet.getX();
			double dz = nEnd.getZ() - feet.getZ();
			double len = Math.sqrt(dx * dx + dz * dz);
			if (len < 3.0) {
				return false;
			}
			double ux = dx / len;
			double uz = dz / len;
			for (int k = 0; k < FLAT_NODES; k++) {
				BlockPos n = p.getNodePos(i + k);
				if (n.getY() != feet.getY()) {
					return false;
				}
				double ox = n.getX() - feet.getX();
				double oz = n.getZ() - feet.getZ();
				if (Math.abs(ox * uz - oz * ux) > 0.6) {
					return false;
				}
				if (l.getBlockState(n.above(2)).blocksMotion() || l.getBlockState(n.above()).blocksMotion()) {
					return false;
				}
				BlockState floor = l.getBlockState(n.below());
				if (!floor.blocksMotion() || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)
						|| floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.CACTUS)) {
					return false;
				}
			}
			// 体の向きが進行方向に合っている（急旋回の最中は跳ばない）
			Vec3 h = dirOfYaw(o.getYRot());
			return h.x * ux + h.z * uz > 0.9;
		}

		/** 40tickごとに実測して calib を寄せる。溶けが進んで遅いときは補正しない。 */
		private void calibrate() {
			if (winStart == null) {
				winStart = o.position();
				winTicks = 0;
				winJumps = 0;
				winClean = true;
			}
			if (++winTicks < 40) {
				return;
			}
			Vec3 now = o.position();
			double bps = SkillUtil.horiz(winStart, now) / 2.0;
			boolean jumped = winJumps >= 2;
			boolean plain = winJumps == 0;
			double target = jumped ? DASHJUMP_BPS : DASH_BPS;
			if (winClean && (jumped || plain) && o.getMelt() < 0.45f && bps > target * 0.45 && bps < target * 1.8) {
				double[] c = CALIB.computeIfAbsent(o, k -> new double[] { 1.0 });
				double f = Math.pow(target / bps, 0.35);
				f = Math.max(0.92, Math.min(1.08, f));
				c[0] = Math.max(0.85, Math.min(1.12, c[0] * f)); // 見た目が「加速」にならないよう補正幅は小さく
			}
			winStart = now;
			winTicks = 0;
			winJumps = 0;
			winClean = true;
		}
	}

	// ---- 泳ぎ ----

	/** 泳ぎの速さ（ブロック/秒）。プレイヤーのダッシュ泳ぎ（約2.2〜2.9）相当。 */
	static final double SWIM_BPS = 2.6;

	/** 足元の水柱の水面の高さ。水の中でなければ NaN。 */
	static double waterSurfaceY(ServerLevel l, FakeMember o) {
		BlockPos p = o.blockPosition();
		if (!l.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
			p = p.below();
			if (!l.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
				return Double.NaN;
			}
		}
		for (int i = 0; i < 40 && l.getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER); i++) {
			p = p.above();
		}
		return p.getY() + l.getFluidState(p).getHeight(l, p);
	}

	/**
	 * 水の中を target（水平位置）へ泳ぐ。底を歩かず、泳ぎ姿勢（Pose.SWIMMING）で進む。
	 * diveY が NaN なら水面を泳ぐ（頭を出して）。数値なら その深さまで潜る（息が減ったら水面へ戻る）。
	 * mob の水中加速は flyingSpeed 0.02 * zza で遅すぎるので、速度を毎tick自前で与える。
	 * UNVERIFIED: Entity.updateSwimming が「ダッシュ中・水中なら isSwimming を保つ」前提。setSwimming(true) を毎tick立てて補う。
	 */
	static void swimStep(FakeMember o, Vec3 target, double diveY) {
		ServerLevel l = SkillUtil.level(o);
		o.setSprinting(true);
		if (!o.isSwimming()) {
			o.setSwimming(true);
		}
		// UNVERIFIED: mob では Entity.updatePose が Pose.SWIMMING にしてくれなかった（CI で pose=STANDING のまま）ので自分で立てる
		if (o.getPose() != net.minecraft.world.entity.Pose.SWIMMING) {
			o.setPose(net.minecraft.world.entity.Pose.SWIMMING);
		}
		double surface = waterSurfaceY(l, o);
		if (Double.isNaN(surface)) {
			surface = o.getY() + 1.0;
		}
		boolean breath = o.getAirSupply() < 90;
		boolean dive = !Double.isNaN(diveY) && !breath && diveY < surface - 1.5;
		double desiredFeet = dive ? diveY : surface + 0.05 - o.getEyeHeight();
		double vy = Mth.clamp((desiredFeet - o.getY()) * 0.35, dive ? -0.15 : -0.12, 0.12);
		Vec3 cur = o.getDeltaMovement();
		double dx = target.x - o.getX();
		double dz = target.z - o.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		double sp = SWIM_BPS / 20.0;
		double vx = 0;
		double vz = 0;
		if (len > 0.05) {
			double k = Math.min(sp, len) / len;
			vx = dx * k;
			vz = dz * k;
			float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f);
			o.setYRot(yaw);
			o.setYHeadRot(yaw);
			o.setYBodyRot(yaw);
		}
		// 岸にぶつかったら（目標が自分より下でなければ）這い上がる
		if (o.horizontalCollision && target.y > o.getY() - 0.5 && !dive) {
			vy = 0.36;
		}
		o.setDeltaMovement(cur.x * 0.4 + vx * 0.6, vy, cur.z * 0.4 + vz * 0.6);
	}

	/** 水深（足元の水柱の連続する水ブロック数）。 */
	static int waterDepth(ServerLevel l, FakeMember o) {
		BlockPos p = o.blockPosition();
		int n = 0;
		for (BlockPos q = p; n < 6 && l.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER); q = q.above()) {
			n++;
		}
		for (BlockPos q = p.below(); n < 12 && l.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER); q = q.below()) {
			n++;
		}
		return n;
	}

	/** プレイヤーと同じ: 頭まで水に潜っているか、水深2以上のときだけ泳ぐ（浅瀬は歩く）。 */
	static boolean shouldSwim(FakeMember o) {
		if (!o.isInWater()) {
			return false;
		}
		return o.isUnderWater() || waterDepth(SkillUtil.level(o), o) >= 2;
	}

	/** 泳ぎ姿勢を戻す（水から上がったとき）。 */
	static void endSwim(FakeMember o) {
		if (o.isSwimming()) {
			o.setSwimming(false);
		}
		if (o.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
			o.setPose(net.minecraft.world.entity.Pose.STANDING);
		}
	}

	/** 参照高さ付近の (x,z) の水柱が「深さ2以上の水面」か。屋根（上空のブロック）は見ない。 */
	static boolean waterColumn(ServerLevel l, double x, double z, double refY) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		if (!l.hasChunkAt(new BlockPos(bx, (int) refY, bz))) {
			return false;
		}
		int top = Mth.floor(refY) + 3;
		for (int y = top; y > Mth.floor(refY) - 14 && y > l.getMinY(); y--) {
			BlockState st = l.getBlockState(new BlockPos(bx, y, bz));
			if (st.isAir()) {
				continue;
			}
			return st.is(Blocks.WATER) && l.getBlockState(new BlockPos(bx, y - 1, bz)).is(Blocks.WATER);
		}
		return false;
	}

	// ---- 着地点の安全確認 ----

	/**
	 * (x,z) で立てる足元の高さ。refY の1つ上から2段下まで（落差2以内）に、溶岩・火・マグマでない床があり、
	 * その上2マスが空いているところ。無ければ NaN（奈落・崖・溶岩）。水面は床として許す。
	 */
	static double groundAt(ServerLevel l, double x, double z, double refY) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int feet = Mth.floor(refY);
		if (!l.hasChunkAt(new BlockPos(bx, feet, bz))) {
			return Double.NaN;
		}
		for (int fy = feet; fy >= feet - 3; fy--) {
			BlockPos fl = new BlockPos(bx, fy, bz);
			BlockState st = l.getBlockState(fl);
			boolean water = st.is(Blocks.WATER);
			if (!st.blocksMotion() && !water) {
				if (st.is(Blocks.LAVA) || isDeadly(st)) {
					return Double.NaN;
				}
				continue;
			}
			if (isDeadly(st) || st.is(Blocks.LAVA)) {
				return Double.NaN;
			}
			BlockState a1 = l.getBlockState(fl.above());
			BlockState a2 = l.getBlockState(fl.above(2));
			if (a1.blocksMotion() || a2.blocksMotion() || a1.is(Blocks.LAVA) || a2.is(Blocks.LAVA) || isDeadly(a1)) {
				return Double.NaN;
			}
			return fy + 1.0;
		}
		return Double.NaN;
	}

	/** away の向きを基準に、3歩先まで安全に降りられる方向（単位ベクトル）。無ければ null。 */
	static Vec3 safeAway(FakeMember o, Vec3 away) {
		ServerLevel l = SkillUtil.level(o);
		double len = Math.sqrt(away.x * away.x + away.z * away.z);
		if (len < 1e-4) {
			away = new Vec3(1, 0, 0);
			len = 1;
		}
		double ax = away.x / len;
		double az = away.z / len;
		float[] offs = { 0, 25, -25, 50, -50, 80, -80, 120, -120 };
		for (float off : offs) {
			double r = Math.toRadians(off);
			double dx = ax * Math.cos(r) - az * Math.sin(r);
			double dz = ax * Math.sin(r) + az * Math.cos(r);
			boolean ok = true;
			for (double k : new double[] { 1.5, 3.0, 5.0 }) {
				if (Double.isNaN(groundAt(l, o.getX() + dx * k, o.getZ() + dz * k, o.getY()))) {
					ok = false;
					break;
				}
			}
			if (ok) {
				return new Vec3(dx, 0, dz);
			}
		}
		return null;
	}

	/** pos の真下（40ブロック以内）に溶岩があるか、床が無く奈落か。橋を掛けてよいかの判定用。 */
	static boolean hazardBelow(ServerLevel l, BlockPos pos) {
		for (int d = 1; d <= 40; d++) {
			BlockPos q = pos.below(d);
			if (q.getY() < l.getMinY()) {
				return true;
			}
			BlockState st = l.getBlockState(q);
			if (st.is(Blocks.LAVA) || isDeadly(st)) {
				return true;
			}
			if (st.blocksMotion() || st.is(Blocks.WATER)) {
				return false;
			}
		}
		return true;
	}

	// ---- 溶岩ブレーキ ----

	/** 歩く入力を止める（MoveControl の前の指示・前後左右の入力を消す）。 */
	static void halt(FakeMember o) {
		o.getMoveControl().setWait();
		o.setSpeed(0.0f);
		o.setZza(0.0f);
		o.setXxa(0.0f);
		o.setSprinting(false);
	}

	/**
	 * 足元の四方（約1.5ブロック先）のどこかが致命的な縁（溶岩・火・10段以上の落下。SafeFooting.deadlyAt）なら、
	 * 反対側の内側の目標点を返す。普通の段差・岸辺・深い水では発動しない。
	 * 行き先とその途中も deadlyAt / groundAt で確かめ、斜め（L字の角の切り欠き）が危なければ軸の向きだけを試す。無ければ null。
	 */
	static Vec3 awayFromDeadlyEdge(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		int sx = 0, sz = 0;
		int bad = 0;
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int[] d : dirs) {
			// 約1.5 ブロック内側に立つ: 縁の手前で殴られても、ノックバック（耐性0.5で約1.2ブロック）で落ちない
			if (SafeFooting.deadlyAt(l, o.getX() + d[0] * 1.45, o.getZ() + d[1] * 1.45, o.getY())) {
				sx -= d[0];
				sz -= d[1];
				bad++;
			}
		}
		if (bad == 0 || bad == 4 || (sx == 0 && sz == 0)) {
			return null;
		}
		java.util.List<double[]> cands = new java.util.ArrayList<>();
		double n = Math.sqrt(sx * sx + sz * sz);
		cands.add(new double[] { sx / n, sz / n });
		if (sx != 0 && sz != 0) {
			cands.add(new double[] { Math.signum(sx), 0 });
			cands.add(new double[] { 0, Math.signum(sz) });
		}
		for (double[] c : cands) {
			for (double k : new double[] { 1.2, 0.8 }) {
				double tx = o.getX() + c[0] * k;
				double tz = o.getZ() + c[1] * k;
				double mx = o.getX() + c[0] * k * 0.5;
				double mz = o.getZ() + c[1] * k * 0.5;
				if (!SafeFooting.deadlyAt(l, tx, tz, o.getY()) && !SafeFooting.deadlyAt(l, mx, mz, o.getY())
						&& !Double.isNaN(groundAt(l, tx, tz, o.getY()))) {
					return new Vec3(tx, o.getY(), tz);
				}
			}
		}
		return null;
	}

	/** 跳んでも安全か: 進む向き（速度か体の向き）の 1〜2.5 ブロック先に、落差2以内の床があり溶岩・火でない。 */
	static boolean safeToJump(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		Vec3 v = o.getDeltaMovement();
		Vec3 d = v.horizontalDistanceSqr() > 1.0E-4 ? new Vec3(v.x, 0, v.z).normalize() : dirOfYaw(o.getYRot());
		for (double k : new double[] { 1.0, 1.8, 2.5 }) {
			BlockPos c = BlockPos.containing(o.getX() + d.x * k, o.getY(), o.getZ() + d.z * k);
			boolean floor = false;
			for (int dd = 1; dd <= 2; dd++) {
				BlockState st = l.getBlockState(c.below(dd));
				if (isDeadly(st)) {
					return false;
				}
				if (st.blocksMotion()) {
					floor = true;
					break;
				}
			}
			if (!floor || isDeadly(l.getBlockState(c))) {
				return false;
			}
		}
		return true;
	}

	static boolean isDeadly(BlockState s) {
		return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK)
				|| s.is(Blocks.CACTUS) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.SWEET_BERRY_BUSH);
	}

	/** 進行方向の1〜2歩先が溶岩（足元が溶岩・床が無くて下が溶岩）なら止まる。止めたら true。 */
	static boolean lavaBrake(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		Vec3 v = o.getDeltaMovement();
		double hx = v.x;
		double hz = v.z;
		double hl = Math.sqrt(hx * hx + hz * hz);
		if (hl < 0.05) {
			return false;
		}
		hx /= hl;
		hz /= hl;
		for (int k = 1; k <= 3; k++) {
			BlockPos c = BlockPos.containing(o.getX() + hx * k, o.getY(), o.getZ() + hz * k);
			if (l.getBlockState(c).is(Blocks.LAVA) || l.getBlockState(c.below()).is(Blocks.LAVA) || isDeadly(l.getBlockState(c))) {
				brake(o);
				return true;
			}
			if (!l.getBlockState(c.below()).blocksMotion() && !l.getBlockState(c).blocksMotion()) {
				for (int d = 1; d <= 4; d++) {
					BlockPos b = c.below(d);
					BlockState s = l.getBlockState(b);
					if (s.is(Blocks.LAVA)) {
						brake(o);
						return true;
					}
					if (s.blocksMotion()) {
						break;
					}
				}
			}
		}
		return false;
	}

	private static void brake(FakeMember o) {
		o.getNavigation().stop();
		o.setSprinting(false);
		Vec3 v = o.getDeltaMovement();
		o.setDeltaMovement(v.x * 0.1, v.y, v.z * 0.1);
	}

	// ---- 水バケツ着地 ----

	/** 5ブロック以上落ちそうで水バケツがあれば、着地点に水を置き、降りたら回収する。 */
	static final class FallGuard {
		private BlockPos placed;
		private int age;
		private int wet;

		void tick(FakeMember o) {
			ServerLevel l = SkillUtil.level(o);
			if (placed != null) {
				age++;
				if (o.isInWater() || o.onGround()) {
					wet++;
				}
				if (wet >= 3 || age > 160) {
					retrieve(o, l);
				}
				return;
			}
			if (o.onGround() || o.isInWater() || o.isInLava()) {
				return;
			}
			Vec3 v = o.getDeltaMovement();
			if (v.y > -0.3) {
				return;
			}
			if (Inv.count(o, Items.WATER_BUCKET) <= 0) {
				return;
			}
			int fx = Mth.floor(o.getX());
			int fz = Mth.floor(o.getZ());
			int top = -1;
			int floorY = Mth.floor(o.getY());
			int min = l.getMinY();
			for (int y = floorY - 1; y >= Math.max(min, floorY - 80); y--) {
				BlockState s = l.getBlockState(new BlockPos(fx, y, fz));
				if (s.blocksMotion() || !s.getFluidState().isEmpty()) {
					top = y;
					break;
				}
			}
			if (top < 0) {
				return;
			}
			BlockState ground = l.getBlockState(new BlockPos(fx, top, fz));
			if (!ground.getFluidState().isEmpty()) {
				return; // 水に落ちるならそのまま（溶岩に水は置けない）
			}
			double gap = o.getY() - (top + 1);
			double expected = o.fallDistance + gap;
			if (expected < 5.0 || gap > 3.2 + Math.max(0, -v.y)) {
				return;
			}
			BlockPos at = new BlockPos(fx, top + 1, fz);
			if (!l.getBlockState(at).canBeReplaced()) {
				return;
			}
			Inv.remove(o, Items.WATER_BUCKET, 1);
			Inv.add(o, new ItemStack(Items.BUCKET));
			l.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
			l.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 1.0f, 1.0f);
			SkillUtil.swing(o);
			o.setXRot(90.0f);
			placed = at;
			age = 0;
			wet = 0;
		}

		private void retrieve(FakeMember o, ServerLevel l) {
			BlockPos p = placed;
			placed = null;
			if (l.getBlockState(p).is(Blocks.WATER)) {
				l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				l.playSound(null, p, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1.0f, 1.0f);
				if (Inv.count(o, Items.BUCKET) > 0) {
					Inv.remove(o, Items.BUCKET, 1);
				}
				Inv.add(o, new ItemStack(Items.WATER_BUCKET));
			}
		}
	}

	// ---- 足場 ----

	private static final Item[] SCAFFOLD = { Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.COBBLED_DEEPSLATE,
			Items.STONE, Items.END_STONE, Items.BLACKSTONE, Items.OAK_PLANKS };

	/** 足場にできるブロックの合計個数。 */
	static int scaffoldCount(FakeMember o) {
		int n = 0;
		for (Item i : SCAFFOLD) {
			n += Inv.count(o, i);
		}
		return n;
	}

	static boolean hasScaffold(FakeMember o) {
		for (Item i : SCAFFOLD) {
			if (Inv.count(o, i) > 0) {
				return true;
			}
		}
		return false;
	}

	/** 足場に使えるブロックを1個取り出して、そのブロックの状態を返す。無ければ null。 */
	static BlockState takeScaffold(FakeMember o) {
		for (Item i : SCAFFOLD) {
			if (Inv.count(o, i) > 0 && i instanceof BlockItem bi) {
				Inv.remove(o, i, 1);
				return bi.getBlock().defaultBlockState();
			}
		}
		return null;
	}

	/** 足場を1個置く。素材が無い・置けない場所なら false。 */
	static boolean placeScaffold(FakeMember o, BlockPos p) {
		ServerLevel l = SkillUtil.level(o);
		if (!l.hasChunkAt(p) || !l.getBlockState(p).canBeReplaced()) {
			return false;
		}
		if (!l.getEntities(o, new net.minecraft.world.phys.AABB(p).deflate(0.05),
				e -> e != o && e.blocksBuilding).isEmpty()) {
			return false;
		}
		BlockState st = takeScaffold(o);
		if (st == null) {
			return false;
		}
		l.setBlock(p, st, 3);
		l.playSound(null, p, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
		SkillUtil.swing(o);
		return true;
	}

	// ---- 柱 ----

	static final int PILLAR_WORKING = 0;
	static final int PILLAR_STEP = 1;
	static final int PILLAR_NO_BLOCK = 2;
	static final int PILLAR_BLOCKED = 3;

	/** 真上に積む（ジャンプ→頂点で足元に置く）。頭上に石があれば digger で掘ってから。 */
	static final class Pillar {
		private BlockPos cell;
		private boolean placed;
		private int waitTicks;
		private int fails;
		int built;

		void reset() {
			cell = null;
			placed = false;
			waitTicks = 0;
			fails = 0;
			built = 0;
		}

		int tick(FakeMember o, Digger digger) {
			ServerLevel l = SkillUtil.level(o);
			SkillUtil.lookAt(o, o.position().add(0, -1, 0));
			Vec3 v = o.getDeltaMovement();
			o.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
			if (o.onGround()) {
				if (cell != null) {
					if (placed && o.getY() >= cell.getY() + 0.9) {
						// 跳んで足元に置けて、その上に乗った時だけ1段と数える
						built++;
						cell = null;
						placed = false;
						fails = 0;
						return PILLAR_STEP;
					}
					// 跳べなかった（関門に止められた等）・置けなかった: 段は数えず、少し待ってから跳び直す
					if (++waitTicks > 4) {
						cell = null;
						placed = false;
						if (++fails >= 3) {
							fails = 0;
							return PILLAR_BLOCKED;
						}
					}
					return PILLAR_WORKING;
				}
				BlockPos head2 = o.blockPosition().above(2);
				if (l.getBlockState(head2).blocksMotion()) {
					if (!digger.active() && !digger.begin(head2)) {
						return PILLAR_BLOCKED;
					}
					digger.tick();
					return PILLAR_WORKING;
				}
				if (!hasScaffold(o)) {
					return PILLAR_NO_BLOCK;
				}
				cell = o.blockPosition();
				placed = false;
				waitTicks = 0;
				o.getJumpControl().jump();
			} else if (cell != null && !placed && o.getY() >= cell.getY() + 1.0 && l.getBlockState(cell).canBeReplaced()) {
				// 跳んで足が cell より1段上がった時だけ置く
				if (!placeScaffold(o, cell)) {
					cell = null;
					return PILLAR_NO_BLOCK;
				}
				placed = true;
			}
			return PILLAR_WORKING;
		}
	}

	static Direction horizontalDir(Vec3 from, Vec3 to) {
		return SkillUtil.horizontalToward(from, to);
	}
}
