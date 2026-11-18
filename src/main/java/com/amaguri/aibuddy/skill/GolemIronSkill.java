package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * アイアンゴーレムから鉄インゴットを集める。
 * ①1発当てて敵対させ ②すぐ反対へ2〜3ブロック離れ ③プレイヤー並みの速さで足元に3段積み（ゴーレムは届かない）
 * ④寄ってきたゴーレムを柱の上から倒し（10秒来なければ降りてやり直す、最大3回） ⑤降りて鉄を拾う。
 * 村人は殴らない。この間の Combat / Evade はゴーレムを脅威にしない（Combat.isThreat）。
 */
final class GolemIronSkill extends Skill {
	private static final int PILLAR_HEIGHT = 3;

	private enum Phase { FIND, APPROACH, BACKOFF, PILLAR, FIGHT, DESCEND, COLLECT }

	/** 殴ったあと、ゴーレムとの体のすき間がこれになるまで離れてから積む（約2〜3ブロック） */
	private static final double BACKOFF_GAP = 3.0;
	/** 柱の上でゴーレムが寄ってくるのを待つ最大 tick（10秒）。来なければ降りてやり直す */
	private static final int WAIT_TICKS = 200;
	private static final int MAX_RETRIES = 3;

	private final int wanted;
	private final Combat.State cs;
	private final Motion.Pillar pillar = new Motion.Pillar();
	private Phase phase = Phase.FIND;
	private IronGolem golem;
	private int startIron;
	private int ticks;
	private int phaseTicks;
	private int farTicks;
	private double baseY;
	private Vec3 deathPos;
	private int golems;
	private int occupied;
	private int retries;

	GolemIronSkill(FakeMember o, SkillRunner r, int count) {
		super(o, r);
		this.wanted = Math.max(1, Math.min(count, 64));
		this.cs = Combat.state(o);
	}

	@Override
	String begin() {
		if (Motion.scaffoldCount(o) < PILLAR_HEIGHT) {
			return "できない: ブロック不足（足元に3段積む分が要る）";
		}
		golem = findGolem();
		if (golem == null) {
			return "できない: 近くにアイアンゴーレムがいない";
		}
		startIron = Inv.count(o, Items.IRON_INGOT);
		return null;
	}

	private IronGolem findGolem() {
		List<IronGolem> list = SkillUtil.level(o).getEntitiesOfClass(IronGolem.class, o.getBoundingBox().inflate(64, 24, 64), IronGolem::isAlive);
		IronGolem best = null;
		double bd = Double.MAX_VALUE;
		for (IronGolem g : list) {
			double d = g.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = g;
			}
		}
		return best;
	}

	private int gained() {
		return Inv.count(o, Items.IRON_INGOT) - startIron;
	}

	private void setPhase(Phase p) {
		phase = p;
		phaseTicks = 0;
	}

	@Override
	void tick() {
		ticks++;
		phaseTicks++;
		Combat.coolDown(o, cs);
		if (ticks > 6000) {
			failWith("ゴーレム狩りに時間がかかりすぎた（鉄 " + gained() + "）");
			return;
		}
		switch (phase) {
			case FIND -> {
				if (gained() >= wanted) {
					done();
					return;
				}
				golem = findGolem();
				if (golem == null) {
					if (gained() > 0) {
						succeed("鉄を" + gained() + "個集めた（ゴーレムがいなくなった）", "アイアンゴーレムから鉄を" + gained() + "個集めた");
					} else {
						failWith("アイアンゴーレムがいない");
					}
					return;
				}
				if (Motion.scaffoldCount(o) < PILLAR_HEIGHT) {
					failWith("できない: ブロック不足（鉄 " + gained() + "）");
					return;
				}
				setPhase(Phase.APPROACH);
			}
			case APPROACH -> approach();
			case BACKOFF -> backOff();
			case PILLAR -> pillarUp();
			case FIGHT -> fight();
			case DESCEND -> descend();
			case COLLECT -> collect();
		}
	}

	/** 体と体の水平のすき間（中心の距離から、両方の幅の半分を引く）。 */
	private double gap() {
		return SkillUtil.horiz(o.position(), golem.position()) - (golem.getBbWidth() + o.getBbWidth()) / 2.0;
	}

	/** ①ゴーレムに近づいて1発当て、敵対させる。 */
	private void approach() {
		if (!golem.isAlive()) {
			setPhase(Phase.FIND);
			return;
		}
		Combat.equipBest(o, cs);
		double d = Combat.distTo(o, golem);
		if (d > Combat.REACH - 0.3) {
			nav.go(golem.position(), 2.0, 1.0);
			nav.tick();
			if (nav.failed() || phaseTicks > 900) {
				nav.stop();
				failWith("ゴーレムに近づけない（鉄 " + gained() + "）");
			}
			return;
		}
		nav.stop();
		Motion.face(o, golem.getEyePosition());
		if (o.onGround() && Combat.hit(o, cs, golem, false)) {
			golem.setTarget(o);
			setPhase(Phase.BACKOFF);
		}
	}

	private void startPillar() {
		Motion.halt(o);
		baseY = Math.floor(o.getY() + 0.01);
		pillar.reset();
		pillar.chainUntil = PILLAR_HEIGHT; // 着地した tick に次を跳ぶ速い積み方
		setPhase(Phase.PILLAR);
	}

	/** ②殴ったらすぐ、ゴーレムと反対へ体のすき間 BACKOFF_GAP まで走って離れる（SafeFooting の関門を通る移動）。 */
	private void backOff() {
		if (!golem.isAlive()) {
			setPhase(Phase.FIND);
			return;
		}
		Vec3 dir = Motion.safeAway(o, o.position().subtract(golem.position()));
		if (o.onGround() && (gap() >= BACKOFF_GAP || dir == null || phaseTicks > 30)) {
			startPillar();
			pillarUp(); // 同じ tick に最初の跳びを出す（1tickでも早く高さを取る）
			return;
		}
		if (dir != null) {
			Vec3 to = o.position().add(dir.scale(4));
			o.setSprinting(true);
			o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
		}
	}

	/** ③プレイヤー並みの速さで3段積む（跳びの頂点前に足元へ置き、着地した tick に次を跳ぶ）。 */
	private void pillarUp() {
		if (phaseTicks > 200) {
			failWith("柱を積めなかった（鉄 " + gained() + "）");
			return;
		}
		int r = pillar.tick(o, digger);
		if (r == Motion.PILLAR_NO_BLOCK) {
			failWith("できない: ブロック不足（鉄 " + gained() + "）");
		} else if (r == Motion.PILLAR_BLOCKED) {
			failWith("頭上が塞がっていて柱を積めない（鉄 " + gained() + "）");
		} else if (r == Motion.PILLAR_OCCUPIED) {
			// ゴーレムが足元の升に重なっていて置けない: 地面にいれば離れて積み直す。柱の途中なら次の跳びで再試行
			if (++occupied > 10) {
				failWith("ゴーレムが重なって柱を積めない（鉄 " + gained() + "）");
			} else if (pillar.built == 0) {
				setPhase(Phase.BACKOFF);
			}
		} else if (r == Motion.PILLAR_STEP && pillar.built >= PILLAR_HEIGHT) {
			farTicks = 0;
			setPhase(Phase.FIGHT);
		}
	}

	/** ゴーレムが柱に来なかった: 降りて、①からやり直す。 */
	private void descend() {
		if (!golem.isAlive()) {
			setPhase(Phase.FIND);
			return;
		}
		if (o.onGround() && o.getY() - baseY < 0.5) {
			setPhase(Phase.APPROACH);
			return;
		}
		if (phaseTicks > 100) {
			failWith("柱から降りられない（鉄 " + gained() + "）");
			return;
		}
		// 柱の上からゴーレムの方へ歩き出して降りる（3段の落下は無傷）
		Vec3 to = golem.position();
		o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, false, 1.0));
	}

	private void fight() {
		if (!golem.isAlive()) {
			deathPos = golem.position();
			golems++;
			setPhase(Phase.COLLECT);
			return;
		}
		boolean high = o.getY() - baseY >= PILLAR_HEIGHT - 0.2;
		if (!high) {
			// 高さが足りない（柱が崩れた等）: 積み直す
			pillar.reset();
			setPhase(Phase.PILLAR);
			return;
		}
		Combat.equipBest(o, cs);
		o.getNavigation().stop();
		o.setSprinting(false);
		Motion.face(o, golem.getEyePosition());
		double d = Combat.distTo(o, golem);
		if (d <= Combat.REACH) {
			farTicks = 0;
			Combat.hit(o, cs, golem, false);
		} else if (++farTicks > WAIT_TICKS) {
			// ④柱の上で10秒待っても来ない: 降りてやり直す（最大3回）
			if (++retries > MAX_RETRIES) {
				failWith("ゴーレムが柱に来ない（鉄 " + gained() + "）");
			} else {
				setPhase(Phase.DESCEND);
			}
			return;
		}
		if (golem.getTarget() == null && d < 12) {
			golem.setTarget(o);
		}
	}

	private void collect() {
		if (gained() >= wanted && phaseTicks > 40) {
			done();
			return;
		}
		if (phaseTicks > 400) {
			setPhase(Phase.FIND);
			return;
		}
		List<ItemEntity> items = SkillUtil.items(o, 12);
		boolean high = o.getY() - baseY >= 1.5;
		Vec3 to = !items.isEmpty() ? items.get(0).position() : deathPos;
		if (high) {
			// 柱から降りる（3ブロックの落下は無傷）。向かう先へ直接歩き出す
			o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, false, 1.0));
			return;
		}
		if (items.isEmpty()) {
			if (phaseTicks > 60) {
				setPhase(Phase.FIND);
			}
			return;
		}
		nav.go(to, 0.6, 1.0);
		nav.setVertTol(3.0);
		nav.tick();
		if (nav.failed()) {
			setPhase(Phase.FIND);
		}
	}

	private void done() {
		succeed("鉄インゴットを" + gained() + "個集めた（ゴーレム " + golems + "体）",
				"アイアンゴーレムから鉄を" + gained() + "個集めた");
	}

	@Override
	String status() {
		return "アイアンゴーレムと戦っている（鉄 " + gained() + "/" + wanted + "）";
	}
}
