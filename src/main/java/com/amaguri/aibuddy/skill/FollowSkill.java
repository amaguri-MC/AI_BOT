package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** プレイヤーについていく。stop するまで続く。 */
final class FollowSkill extends Skill {
	private String name;
	private ServerPlayer target;
	private int lostTicks;
	private int failures;

	FollowSkill(FakeMember o, SkillRunner r, String name) {
		super(o, r);
		this.name = name;
	}

	@Override
	String begin() {
		if (name == null || name.isBlank()) {
			// 名前の省略は「一番近い人」（スペクテイターは除く）
			ServerPlayer near = SkillUtil.nearestPlayer(o, 256);
			if (near == null) {
				return "できない: 近くにプレイヤーがいない";
			}
			name = near.getScoreboardName();
		}
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
			// 3ブロック以上離れたらダッシュ（Nav が距離で切り替える）
			nav.go(tp, 2.5, 1.0);
			nav.tick();
		} else {
			nav.stop();
			if (o.tickCount % 20 == 0) {
				SkillUtil.lookAt(o, target.getEyePosition());
			}
		}
		if (nav.failed()) {
			// 指示待ちで止まらない: 少し待って経路を引き直す。5回続けて駄目なら諦める
			nav.stop();
			if (++failures >= 5) {
				finish("追いつけない: " + name, name + "に追いつけない");
			}
		}
	}

	@Override
	String status() {
		return name + "についていっている";
	}
}
