package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.world.phys.Vec3;

/** 座標まで歩く。 */
final class GotoSkill extends Skill {
	private final Vec3 target;
	private int ticks;
	private final int timeout;

	GotoSkill(FakeMember o, SkillRunner r, double x, double y, double z) {
		super(o, r);
		this.target = new Vec3(x, y, z);
		double d = Math.sqrt(o.position().distanceToSqr(target));
		this.timeout = 1200 + (int) (d * 14);
	}

	@Override
	String begin() {
		if (target.y < o.level().getMinY() - 64 || target.y > o.level().getMinY() + o.level().getHeight() + 64) {
			return "できない: 高さが範囲外";
		}
		nav.go(target, 1.5, 1.0);
		return null;
	}

	@Override
	void tick() {
		ticks++;
		nav.tick();
		if (nav.arrived()) {
			finish("着いた " + SkillUtil.pos(target), "目的地に着いた " + SkillUtil.pos(target));
		} else if (nav.failed() || ticks > timeout) {
			finish("たどり着けなかった " + SkillUtil.pos(target), "目的地にたどり着けなかった " + SkillUtil.pos(target));
		}
	}

	@Override
	String status() {
		return "座標 " + SkillUtil.pos(target) + " へ移動中（あと" + Math.round(nav.distance()) + "m）";
	}
}
