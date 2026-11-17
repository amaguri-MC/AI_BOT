package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 逃げる（安全地帯へ）。上級者は無駄に戦わない。
 * 柱: 足元に3段積んで上で待つ / 壁: 弓持ちがいるときは敵の方向に2段の壁 / 橋: 足場のない方へ4マス / 走る: ブロックが無いとき。
 * 待つのは最大60秒。敵が離れた・昼になった・体力が戻ったら降りて進行を再開（Autopilot は避難中だけ pause）。
 */
final class Evade {
	enum Mode { PILLAR, COVER, BRIDGE, RUN, WAIT, DESCEND }

	static final int MAX_WAIT = 20 * 60;
	static final int PILLAR_HEIGHT = 3;

	boolean active;
	Mode mode = Mode.RUN;
	int ticks;
	int waited;
	int calm;
	private int startY;
	private int built;
	private int runTicks;
	private int bridged;
	private Vec3 away = Vec3.ZERO;
	private Vec3 home;
	private final Motion.Pillar pillar = new Motion.Pillar();
	private Digger digger;
	private final java.util.ArrayDeque<Integer> builds = new java.util.ArrayDeque<>();
	private boolean restricted;
	private int descendTicks;

	boolean waitedOut() {
		return waited >= MAX_WAIT;
	}

	/** 避難を始める。aggr は今こちらを狙っている敵。 */
	void begin(FakeMember o, List<LivingEntity> aggr) {
		active = true;
		ticks = 0;
		waited = 0;
		calm = 0;
		built = 0;
		bridged = 0;
		runTicks = 0;
		descendTicks = 0;
		pillar.reset();
		if (digger == null) {
			digger = new Digger(o);
		}
		startY = o.blockPosition().getY();
		home = o.position();
		away = awayFrom(o, aggr);
		o.getNavigation().stop();
		o.setSprinting(false);
		boolean ranged = false;
		for (LivingEntity e : aggr) {
			ranged |= Combat.isRanged(e);
		}
		ServerLevel lv = SkillUtil.level(o);
		// ネザー・エンドでは橋と横への降り方を使わない（溶岩・奈落）
		restricted = SkillUtil.isNether(o) || SkillUtil.inDimension(o, net.minecraft.world.level.Level.END);
		// 同じ場所で何度も建て直してブロックを使い切らない: 2分に2回まで
		while (!builds.isEmpty() && o.tickCount - builds.peekFirst() > 2400) {
			builds.pollFirst();
		}
		int blocks = builds.size() >= 2 ? 0 : Motion.scaffoldCount(o);
		o.autopilot().holdForEvade(true);
		if (ranged && blocks >= 2) {
			mode = Mode.COVER;
		} else if (blocks >= PILLAR_HEIGHT && headroom(o)) {
			mode = Mode.PILLAR;
		} else if (!restricted && blocks >= 4 && gapAhead(o) && bridgeSafe(o)) {
			mode = Mode.BRIDGE;
		} else {
			mode = Mode.RUN;
		}
		if (mode != Mode.RUN) {
			builds.addLast(o.tickCount);
		}
	}

	/** 逃げる方向の先が溶岩・奈落の上でないか。 */
	private boolean bridgeSafe(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		BlockPos f = o.blockPosition().below();
		for (int k = 1; k <= 4; k++) {
			BlockPos c = f.offset((int) Math.round(away.x * k), 0, (int) Math.round(away.z * k));
			if (Motion.hazardBelow(l, c)) {
				return false;
			}
		}
		return true;
	}

	private static Vec3 awayFrom(FakeMember o, List<LivingEntity> aggr) {
		double x = 0;
		double z = 0;
		for (LivingEntity e : aggr) {
			x += o.getX() - e.getX();
			z += o.getZ() - e.getZ();
		}
		double len = Math.sqrt(x * x + z * z);
		return len < 1e-3 ? new Vec3(1, 0, 0) : new Vec3(x / len, 0, z / len);
	}

	private boolean headroom(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		BlockPos p = o.blockPosition();
		for (int dy = 2; dy <= PILLAR_HEIGHT + 2; dy++) {
			if (l.getBlockState(p.above(dy)).blocksMotion()) {
				return false;
			}
		}
		return true;
	}

	/** 逃げる方向の2〜3マス先に床が無い（水・穴）なら橋を掛けられる。 */
	private boolean gapAhead(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		BlockPos f = o.blockPosition().below();
		for (int k = 2; k <= 3; k++) {
			BlockPos c = f.offset((int) Math.round(away.x * k), 0, (int) Math.round(away.z * k));
			if (l.getBlockState(c).blocksMotion()) {
				return false;
			}
		}
		return true;
	}

	/** aggr: 今こちらを狙っている敵（空なら静か）。day: 昼か。戻り値は終了したら false。 */
	boolean tick(FakeMember o, List<LivingEntity> aggr, boolean day) {
		if (!active) {
			return false;
		}
		ServerLevel l = SkillUtil.level(o);
		ticks++;
		o.autopilot().holdForEvade(true); // pause は使わず、進行の tick だけ止める
		double nearest = Double.MAX_VALUE;
		for (LivingEntity e : aggr) {
			nearest = Math.min(nearest, e.distanceTo(o));
		}
		if (!aggr.isEmpty()) {
			away = awayFrom(o, aggr);
		}
		switch (mode) {
			case PILLAR -> {
				int r = pillar.tick(o, digger);
				if (r == Motion.PILLAR_STEP) {
					built++;
					if (built >= PILLAR_HEIGHT) {
						mode = Mode.WAIT;
					}
				} else if (r == Motion.PILLAR_NO_BLOCK || r == Motion.PILLAR_BLOCKED || ticks > 160) {
					mode = Mode.RUN;
				}
			}
			case COVER -> cover(o, l, aggr);
			case BRIDGE -> bridge(o, l);
			case RUN -> run(o, l, nearest);
			case WAIT -> {
				waited++;
				o.getNavigation().stop();
				o.setSprinting(false);
				if (!aggr.isEmpty()) {
					Motion.face(o, aggr.get(0).getEyePosition());
				}
				if (o.getHealth() < 14 && Combat.hasFood(o)) {
					Combat.lowHealthRoutine(o, null);
				}
				calm = nearest > 10 ? calm + 1 : 0;
				boolean healed = o.getHealth() >= 14;
				if (waited >= MAX_WAIT || calm >= 100 || (day && healed && nearest > 8)) {
					mode = Mode.DESCEND;
					descendTicks = 0;
				}
			}
			case DESCEND -> {
				descendTicks++;
				boolean high = o.getY() > startY + 1.5;
				if (!high || descendTicks > 200) {
					finish(o);
					return false;
				}
				if (restricted) {
					// ネザー・エンド: 横へ降りず、真下を掘って降りる
					if (!digDown(o, l)) {
						finish(o);
						return false;
					}
				} else {
					Vec3 d = Motion.safeAway(o, away);
					if (d == null) {
						if (!digDown(o, l)) {
							finish(o);
							return false;
						}
					} else {
						Vec3 to = o.position().add(d.scale(2.5)); // 床があり落差2以内と確認済みの側へ降りる
						o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, false, 1.0));
					}
				}
			}
		}
		if (ticks > MAX_WAIT + 400) {
			finish(o);
			return false;
		}
		return active;
	}

	/** 足元の柱を1つ掘る。掘れなければ false。 */
	private boolean digDown(FakeMember o, ServerLevel l) {
		BlockPos below = o.blockPosition().below();
		if (below.getY() < startY) {
			return false;
		}
		if (!digger.active() && !digger.begin(below)) {
			return false;
		}
		return digger.tick() != Digger.ABORTED;
	}

	private void run(FakeMember o, ServerLevel l, double nearest) {
		runTicks++;
		// 走る先は床があり落差2以内・溶岩でない方向だけ。無ければ走らず通常の戦いに戻す
		Vec3 dir = Motion.safeAway(o, away);
		if (dir == null) {
			finish(o);
			return;
		}
		o.setSprinting(true);
		Vec3 to = o.position().add(dir.scale(10));
		o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
		if (o.onGround() && o.tickCount % 11 == 0 && Motion.safeToJump(o)) {
			o.getJumpControl().jump();
		}
		Motion.lavaBrake(o);
		if (nearest > 14 || runTicks > 120) {
			finish(o);
			active = false;
		}
	}

	/** 敵の方向に高さ2の壁（弓の矢を遮る）。置けたら陰で待つ。 */
	private void cover(FakeMember o, ServerLevel l, List<LivingEntity> aggr) {
		Vec3 toward = away.scale(-1);
		BlockPos base = o.blockPosition().offset((int) Math.round(toward.x), 0, (int) Math.round(toward.z));
		if (base.equals(o.blockPosition())) {
			base = o.blockPosition().east();
		}
		boolean ok = true;
		for (int dy = 0; dy <= 1; dy++) {
			BlockPos c = base.above(dy);
			if (!l.getBlockState(c).blocksMotion()) {
				ok &= Motion.placeScaffold(o, c);
			}
		}
		if (ok) {
			mode = Mode.WAIT;
		} else {
			mode = Mode.RUN;
		}
	}

	/** 敵から離れる向きに橋を掛けて渡る。 */
	private void bridge(FakeMember o, ServerLevel l) {
		if (bridged >= 4 || ticks > 200) {
			mode = Mode.WAIT;
			return;
		}
		BlockPos floor = o.blockPosition().below();
		BlockPos next = floor.offset((int) Math.round(away.x), 0, (int) Math.round(away.z));
		if (next.equals(floor)) {
			mode = Mode.RUN;
			return;
		}
		o.setShiftKeyDown(true);
		if (restricted || Motion.hazardBelow(l, next)) {
			o.setShiftKeyDown(false);
			mode = Mode.RUN; // 溶岩・奈落の上には橋を出さない
			return;
		}
		if (!l.getBlockState(next).blocksMotion()) {
			if (!Motion.placeScaffold(o, next)) {
				o.setShiftKeyDown(false);
				mode = Mode.RUN;
			} else {
				bridged++;
			}
			return;
		}
		Vec3 c = Vec3.atBottomCenterOf(next.above());
		o.getMoveControl().setWantedPosition(c.x, c.y, c.z, Motion.speedMod(o, false, 1.0));
	}

	private void finish(FakeMember o) {
		active = false;
		o.autopilot().holdForEvade(false);
		o.setShiftKeyDown(false);
		o.setSprinting(false);
		if (digger != null) {
			digger.cancel();
		}
	}

	void abort(FakeMember o) {
		if (active) {
			finish(o);
		}
	}
}
