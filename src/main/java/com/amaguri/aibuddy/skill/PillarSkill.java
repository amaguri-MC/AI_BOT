package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

/** 真上に height ブロック積んで登る。 */
final class PillarSkill extends Skill {
	private final int height;
	private final Motion.Pillar pillar = new Motion.Pillar();
	private int ticks;

	PillarSkill(FakeMember o, SkillRunner r, int height) {
		super(o, r);
		this.height = Math.max(1, Math.min(height, 80));
	}

	@Override
	String begin() {
		if (!Motion.hasScaffold(o)) {
			return "できない: 足場にするブロックが無い";
		}
		return null;
	}

	@Override
	void tick() {
		ticks++;
		if (ticks > 60 + height * 40) {
			finish("柱を積みきれなかった（" + pillar.built + "/" + height + "）", null);
			return;
		}
		int r = pillar.tick(o, digger);
		if (r == Motion.PILLAR_NO_BLOCK) {
			finish("できない: ブロックが足りない（" + pillar.built + "/" + height + "）", null);
		} else if (r == Motion.PILLAR_BLOCKED) {
			finish("頭上が掘れない（" + pillar.built + "/" + height + "）", null);
		} else if (r == Motion.PILLAR_STEP && pillar.built >= height) {
			finish("柱を" + height + "ブロック積んだ 高さ " + o.blockPosition().getY(), null);
		}
	}

	@Override
	String status() {
		return "柱を積んでいる（" + pillar.built + "/" + height + "）";
	}
}
