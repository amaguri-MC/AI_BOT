package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** スキル共通の小道具。 */
final class SkillUtil {
	private SkillUtil() {
	}

	static ServerLevel level(FakeMember o) {
		return (ServerLevel) o.level();
	}

	static boolean isNether(FakeMember o) {
		// dimensionType().hasCeiling() は天井のある次元（ネザー）で true
		return o.level().dimensionType().hasCeiling();
	}

	static boolean inDimension(FakeMember o, net.minecraft.resources.ResourceKey<Level> key) {
		return o.level().dimension().equals(key);
	}

	static void lookAt(FakeMember o, Vec3 p) {
		o.getLookControl().setLookAt(p.x, p.y, p.z);
	}

	static void swing(FakeMember o) {
		o.swing(InteractionHand.MAIN_HAND);
	}

	static String pos(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	static String pos(Vec3 p) {
		return Math.round(p.x) + " " + Math.round(p.y) + " " + Math.round(p.z);
	}

	static double horiz(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	static Direction horizontalToward(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	static ServerPlayer findPlayer(FakeMember o, String name) {
		if (name == null) {
			return null;
		}
		for (ServerPlayer p : level(o).players()) {
			if (p.getScoreboardName().equalsIgnoreCase(name.trim())) {
				return p;
			}
		}
		return null;
	}

	static ServerPlayer nearestPlayer(FakeMember o, double range) {
		ServerPlayer best = null;
		double bd = range * range;
		for (ServerPlayer p : level(o).players()) {
			if (p.isSpectator()) {
				continue;
			}
			double d = p.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}

	/** 歩いて立てそうな高さを返す。読み込まれていなければ refY。 */
	static Vec3 snap(FakeMember o, double x, double z, double refY) {
		ServerLevel l = level(o);
		int ix = (int) Math.floor(x);
		int iz = (int) Math.floor(z);
		if (!l.hasChunkAt(new BlockPos(ix, (int) refY, iz))) {
			return new Vec3(x, refY, z);
		}
		if (!isNether(o)) {
			int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz);
			return new Vec3(x, y, z);
		}
		int start = (int) refY + 6;
		for (int y = start; y > (int) refY - 24 && y > l.getMinY() + 1; y--) {
			BlockPos feet = new BlockPos(ix, y, iz);
			if (l.getBlockState(feet.below()).blocksMotion() && !l.getBlockState(feet).blocksMotion()
					&& !l.getBlockState(feet.above()).blocksMotion() && l.getBlockState(feet.below()).getFluidState().isEmpty()) {
				return new Vec3(x, y, z);
			}
		}
		return new Vec3(x, refY, z);
	}

	static boolean isLiquidBlock(BlockState s) {
		return !s.getFluidState().isEmpty();
	}

	static boolean nearLava(ServerLevel l, BlockPos p) {
		for (Direction d : Direction.values()) {
			if (l.getBlockState(p.relative(d)).is(Blocks.LAVA)) {
				return true;
			}
		}
		return false;
	}

	static List<ItemEntity> items(FakeMember o, double r) {
		AABB box = o.getBoundingBox().inflate(r, Math.min(r, 6), r);
		List<ItemEntity> list = new ArrayList<>(level(o).getEntitiesOfClass(ItemEntity.class, box, e -> !e.isRemoved()));
		list.sort((a, b) -> Double.compare(a.distanceToSqr(o), b.distanceToSqr(o)));
		return list;
	}
}
