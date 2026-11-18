package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * アイアンゴーレムから鉄インゴットを集める。
 * そばまで行って足元に3段積み（ゴーレムは届かない）、柱の上から殴って敵対させて倒し、降りて鉄を拾う。
 * 村人は殴らない。この間の Combat / Evade はゴーレムを脅威にしない（Combat.isThreat）。
 */
final class GolemIronSkill extends Skill {
	private static final int PILLAR_HEIGHT = 3;

	private enum Phase { FIND, APPROACH, BACKOFF, PILLAR, FIGHT, COLLECT }

	/** 柱を積む場所: ゴーレムとの体のすき間がこの範囲（重ならず、柱の上から手が届く） */
	private static final double PILLAR_GAP_MIN = 1.0;
	private static final double PILLAR_GAP_MAX = 1.8;

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
			case COLLECT -> collect();
		}
	}

	/** 体と体の水平のすき間（中心の距離から、両方の幅の半分を引く）。 */
	private double gap() {
		return SkillUtil.horiz(o.position(), golem.position()) - (golem.getBbWidth() + o.getBbWidth()) / 2.0;
	}

	/**
	 * ゴーレムのそば（すき間 PILLAR_GAP_MIN〜PILLAR_GAP_MAX）まで行き、まだ殴らずに3段積む。
	 * 殴ってから積むと、追ってきたゴーレム（歩きと同じ速さ）に積む途中で打ち上げられる・升に重なるので、
	 * 敵対させるのは柱の上（手が届かない高さ）から最初の1発で。
	 */
	private void approach() {
		if (!golem.isAlive()) {
			setPhase(Phase.FIND);
			return;
		}
		Combat.equipBest(o, cs);
		double g = gap();
		if (g > PILLAR_GAP_MAX) {
			nav.go(golem.position(), PILLAR_GAP_MAX + 0.6, 1.0);
			nav.tick();
			if (nav.failed() || phaseTicks > 900) {
				nav.stop();
				failWith("ゴーレムに近づけない（鉄 " + gained() + "）");
			}
			return;
		}
		nav.stop();
		if (g < PILLAR_GAP_MIN) {
			setPhase(Phase.BACKOFF); // 近すぎる（升に重なる）: 少し離れてから積む
			return;
		}
		if (o.onGround()) {
			startPillar();
		}
	}

	private void startPillar() {
		Motion.halt(o);
		baseY = Math.floor(o.getY() + 0.01);
		pillar.reset();
		setPhase(Phase.PILLAR);
	}

	/** ゴーレムが近すぎて足元の升に重なる時: 水平のすき間 PILLAR_GAP_MIN 以上まで少し離れてから積む。 */
	private void backOff() {
		if (!golem.isAlive()) {
			setPhase(Phase.FIND);
			return;
		}
		Vec3 dir = Motion.safeAway(o, o.position().subtract(golem.position()));
		if (o.onGround() && (gap() >= PILLAR_GAP_MIN + 0.3 || dir == null || phaseTicks > 60)) {
			startPillar();
			return;
		}
		if (dir != null) {
			Motion.face(o, golem.getEyePosition());
			Vec3 to = o.position().add(dir.scale(3));
			o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, false, 1.0));
		}
	}

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
			// ゴーレムが足元の升に重なっていて置けない: 地面にいれば少し離れて積み直す。柱の途中なら次の跳びで再試行
			if (++occupied > 10) {
				failWith("ゴーレムが重なって柱を積めない（鉄 " + gained() + "）");
			} else if (pillar.built == 0) {
				setPhase(Phase.BACKOFF);
			}
		} else if (r == Motion.PILLAR_STEP && pillar.built >= PILLAR_HEIGHT) {
			setPhase(Phase.FIGHT);
		}
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
		} else if (++farTicks > 300) {
			failWith("ゴーレムが柱に来ない（鉄 " + gained() + "）");
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
