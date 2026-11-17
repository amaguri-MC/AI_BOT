package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 方向を決めて区間ごとに歩き続ける。 */
final class ExploreSkill extends Skill {
	private static final int MAX_LEGS = 15;

	private final String direction;
	private double yaw; // ラジアン。0=+X方向（東）、π/2=+Z方向（南）
	private int legs;
	private int legTicks;

	ExploreSkill(FakeMember o, SkillRunner r, String direction) {
		super(o, r);
		this.direction = direction;
	}

	@Override
	String begin() {
		yaw = parse(direction);
		nextLeg();
		return null;
	}

	private double parse(String d) {
		if (d == null) {
			return o.getRandom().nextDouble() * Math.PI * 2;
		}
		return switch (d.toLowerCase().trim()) {
			case "east", "東" -> 0;
			case "southeast", "南東" -> Math.PI / 4;
			case "south", "南" -> Math.PI / 2;
			case "southwest", "南西" -> Math.PI * 3 / 4;
			case "west", "西" -> Math.PI;
			case "northwest", "北西" -> Math.PI * 5 / 4;
			case "north", "北" -> Math.PI * 3 / 2;
			case "northeast", "北東" -> Math.PI * 7 / 4;
			default -> o.getRandom().nextDouble() * Math.PI * 2;
		};
	}

	private void nextLeg() {
		legs++;
		legTicks = 0;
		Vec3 p = o.position();
		for (int attempt = 0; attempt < 6; attempt++) {
			double a = yaw + (o.getRandom().nextDouble() - 0.5) * (attempt == 0 ? 0.5 : 1.8);
			double len = 28 + o.getRandom().nextInt(16);
			Vec3 t = SkillUtil.snap(o, p.x + Math.cos(a) * len, p.z + Math.sin(a) * len, p.y);
			BlockPos below = BlockPos.containing(t.x, t.y - 1, t.z);
			boolean wet = !SkillUtil.level(o).getBlockState(below).getFluidState().isEmpty()
					|| SkillUtil.level(o).getBlockState(below).is(Blocks.LAVA);
			if (!wet || attempt == 5) {
				nav.go(t, 3.0, 1.0);
				return;
			}
		}
	}

	@Override
	void tick() {
		legTicks++;
		nav.tick();
		if (nav.arrived() || nav.failed() || legTicks > 900) {
			if (legs >= MAX_LEGS) {
				finish("探検を終えた 座標 " + SkillUtil.pos(o.blockPosition()), "探検を終えた 座標 " + SkillUtil.pos(o.blockPosition()));
				return;
			}
			if (nav.failed()) {
				yaw += Math.PI / 2 + o.getRandom().nextDouble();
			}
			nextLeg();
		}
	}

	@Override
	String status() {
		return "探検中（" + legs + "/" + MAX_LEGS + "区間目）";
	}
}
