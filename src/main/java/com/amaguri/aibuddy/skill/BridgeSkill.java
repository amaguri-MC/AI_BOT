package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** 指定方向に length マス、足場を置きながら渡る（置いてから進む）。 */
final class BridgeSkill extends Skill {
	private final String dirText;
	private final int length;
	private Direction dir;
	private int advanced;
	private int ticks;

	BridgeSkill(FakeMember o, SkillRunner r, String direction, int length) {
		super(o, r);
		this.dirText = direction;
		this.length = Math.max(1, Math.min(length, 64));
	}

	static Direction parse(FakeMember o, String d) {
		if (d == null) {
			return Direction.fromYRot(o.getYRot());
		}
		return switch (d.toLowerCase().trim()) {
			case "north", "北" -> Direction.NORTH;
			case "south", "南" -> Direction.SOUTH;
			case "east", "東" -> Direction.EAST;
			case "west", "西" -> Direction.WEST;
			default -> Direction.fromYRot(o.getYRot());
		};
	}

	@Override
	String begin() {
		dir = parse(o, dirText);
		if (!Motion.hasScaffold(o)) {
			return "できない: 足場にするブロックが無い";
		}
		return null;
	}

	@Override
	void tick() {
		ticks++;
		if (advanced >= length) {
			o.setShiftKeyDown(false);
			finish(length + "マスの橋を渡った", null);
			return;
		}
		if (ticks > 40 + length * 30) {
			o.setShiftKeyDown(false);
			finish("橋を渡りきれなかった（" + advanced + "/" + length + "）", null);
			return;
		}
		ServerLevel l = SkillUtil.level(o);
		BlockPos floor = o.blockPosition().below();
		BlockPos next = floor.relative(dir);
		o.setShiftKeyDown(true);
		SkillUtil.lookAt(o, Vec3.atCenterOf(next));
		if (l.getBlockState(next.above()).blocksMotion() || l.getBlockState(next.above(2)).blocksMotion()) {
			o.setShiftKeyDown(false);
			finish("橋の先がふさがっている（" + advanced + "/" + length + "）", null);
			return;
		}
		if (!l.getBlockState(next).blocksMotion()) {
			if (!Motion.placeScaffold(o, next)) {
				o.setShiftKeyDown(false);
				finish("できない: ブロックが足りない（" + advanced + "/" + length + "）", null);
			}
			return;
		}
		Vec3 c = Vec3.atBottomCenterOf(next.above());
		o.getMoveControl().setWantedPosition(c.x, c.y, c.z, Motion.speedMod(o, false, 1.0));
		if (Math.abs(o.getX() - c.x) < 0.45 && Math.abs(o.getZ() - c.z) < 0.45) {
			advanced++;
		}
	}

	@Override
	void onStop() {
		super.onStop();
		o.setShiftKeyDown(false);
	}

	@Override
	String status() {
		return "橋をかけている（" + advanced + "/" + length + "）";
	}
}
