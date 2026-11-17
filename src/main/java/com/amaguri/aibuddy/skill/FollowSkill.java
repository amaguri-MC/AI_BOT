package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** プレイヤーについていく。stop するまで続く。 */
final class FollowSkill extends Skill {
	private final String name;
	private ServerPlayer target;
	private int lostTicks;

	FollowSkill(FakeMember o, SkillRunner r, String name) {
		super(o, r);
		this.name = name;
	}

	@Override
	String begin() {
		target = SkillUtil.findPlayer(o, name);
		if (target == null) {
			return "できない: " + name + " が見つからない（同じ世界にいない）";
		}
		return null;
	}

	@Override
	void tick() {
		target = SkillUtil.findPlayer(o, name);
		if (target == null) {
			if (++lostTicks > 200) {
				finish("見失った: " + name, name + "を見失った");
			}
			return;
		}
		lostTicks = 0;
		double d = Math.sqrt(target.distanceToSqr(o));
		Vec3 tp = target.position();
		if (d > 3.5) {
			nav.setSprint(d > 10);
			nav.go(tp, 2.5, d > 10 ? 1.3 : 1.0);
			nav.tick();
		} else {
			nav.stop();
			if (o.tickCount % 20 == 0) {
				SkillUtil.lookAt(o, target.getEyePosition());
			}
		}
		if (nav.failed()) {
			finish("追いつけない: " + name, name + "に追いつけない");
		}
	}

	@Override
	String status() {
		return name + "についていっている";
	}
}
