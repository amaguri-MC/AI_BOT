package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.server.level.ServerPlayer;

/** jump / crouch / look_at_player / wave。短い動作。 */
final class EmoteSkill extends Skill {
	private final String kind;
	private int t;

	EmoteSkill(FakeMember o, SkillRunner r, String kind) {
		super(o, r);
		this.kind = kind == null ? "" : kind;
	}

	@Override
	String begin() {
		switch (kind) {
			case "jump", "crouch", "look_at_player", "wave" -> {
				return null;
			}
			default -> {
				return "できない: 知らない動作 " + kind;
			}
		}
	}

	@Override
	void tick() {
		t++;
		ServerPlayer p = SkillUtil.nearestPlayer(o, 32);
		switch (kind) {
			case "jump" -> {
				if ((t == 1 || t == 12 || t == 24) && o.onGround()) {
					o.getJumpControl().jump();
				}
				if (t > 30) {
					finish("ジャンプした", null);
				}
			}
			case "crouch" -> {
				o.setShiftKeyDown(t % 10 < 5);
				if (t > 40) {
					finish("しゃがんだ", null);
				}
			}
			case "look_at_player" -> {
				if (p != null) {
					SkillUtil.lookAt(o, p.getEyePosition());
				}
				if (t > 50) {
					finish("プレイヤーを見た", null);
				}
			}
			case "wave" -> {
				if (p != null) {
					SkillUtil.lookAt(o, p.getEyePosition());
				}
				if (t % 8 == 1) {
					SkillUtil.swing(o);
				}
				if (t > 40) {
					finish("手を振った", null);
				}
			}
			default -> finish("", null);
		}
	}

	@Override
	void onStop() {
		super.onStop();
		o.setShiftKeyDown(false);
	}

	@Override
	String status() {
		return switch (kind) {
			case "jump" -> "ジャンプしている";
			case "crouch" -> "しゃがんでいる";
			case "look_at_player" -> "プレイヤーを見ている";
			default -> "手を振っている";
		};
	}
}
