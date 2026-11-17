package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.world.entity.item.ItemEntity;

/** 近くの落ちた物を歩いて拾う。拾うこと自体は SkillRunner が常時やっている。 */
final class CollectSkill extends Skill {
	private int startPicked;
	private int idle;
	private int ticks;

	CollectSkill(FakeMember o, SkillRunner r) {
		super(o, r);
	}

	@Override
	String begin() {
		startPicked = runner.pickedUp;
		if (SkillUtil.items(o, 24).isEmpty()) {
			return "できない: 近くに落ちている物が無い";
		}
		return null;
	}

	@Override
	void tick() {
		ticks++;
		List<ItemEntity> items = SkillUtil.items(o, 24);
		if (items.isEmpty() || ticks > 2400) {
			if (++idle > 20 || ticks > 2400) {
				int n = runner.pickedUp - startPicked;
				finish("拾い終わった（" + n + "個）", null);
			}
			return;
		}
		idle = 0;
		ItemEntity near = items.get(0);
		nav.go(near.position(), 0.6, 1.2);
		nav.tick();
		if (nav.failed()) {
			// 取れない物は諦めて終わる
			finish("拾えない物があった（" + (runner.pickedUp - startPicked) + "個拾った）", null);
		}
	}

	@Override
	String status() {
		return "落ちている物を拾っている（" + (runner.pickedUp - startPicked) + "個）";
	}
}
