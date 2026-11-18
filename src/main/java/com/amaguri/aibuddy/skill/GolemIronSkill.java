package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * アイアンゴーレムから鉄インゴットを集める。
 * 近づいて1発殴って敵対させ、足元に3段積んで（ゴーレムは届かない）柱の上から倒し、降りて鉄を拾う。
 * 村人は殴らない。この間の Combat / Evade はゴーレムを脅威にしない（Combat.isThreat）。
 */
final class GolemIronSkill extends Skill {
	private static final int PILLAR_HEIGHT = 3;

	private enum Phase { FIND, APPROACH, PILLAR, FIGHT, COLLECT }

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
			finish("ゴーレム狩りに時間がかかりすぎた（鉄 " + gained() + "）", null);
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
					finish(gained() > 0 ? "鉄を" + gained() + "個集めた（ゴーレムがいなくなった）" : "アイアンゴーレムがいない", null);
					return;
				}
				if (Motion.scaffoldCount(o) < PILLAR_HEIGHT) {
					finish("できない: ブロック不足（鉄 " + gained() + "）", null);
					return;
				}
				setPhase(Phase.APPROACH);
			}
			case APPROACH -> approach();
			case PILLAR -> pillarUp();
			case FIGHT -> fight();
			case COLLECT -> collect();
		}
	}

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
				finish("ゴーレムに近づけない（鉄 " + gained() + "）", null);
			}
			return;
		}
		nav.stop();
		Motion.face(o, golem.getEyePosition());
		if (Combat.hit(o, cs, golem, false)) {
			// 1発殴って敵対させる（念のためこちらを狙わせる）
			golem.setTarget(o);
			baseY = o.getY();
			pillar.reset();
			setPhase(Phase.PILLAR);
		}
	}

	private void pillarUp() {
		if (phaseTicks > 200) {
			finish("柱を積めなかった（鉄 " + gained() + "）", null);
			return;
		}
		int r = pillar.tick(o, digger);
		if (r == Motion.PILLAR_NO_BLOCK) {
			finish("できない: ブロック不足（鉄 " + gained() + "）", null);
		} else if (r == Motion.PILLAR_BLOCKED) {
			finish("頭上が塞がっていて柱を積めない（鉄 " + gained() + "）", null);
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
			finish("ゴーレムが柱に来ない（鉄 " + gained() + "）", null);
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
		finish("鉄インゴットを" + gained() + "個集めた（ゴーレム " + golems + "体）",
				"アイアンゴーレムから鉄を" + gained() + "個集めた");
	}

	@Override
	String status() {
		return "アイアンゴーレムと戦っている（鉄 " + gained() + "/" + wanted + "）";
	}
}
