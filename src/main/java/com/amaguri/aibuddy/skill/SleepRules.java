package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;

/** 寝てよいかの判定。Autopilot（run/Plan）が「夜なら sleep を最優先」に使う。 */
public final class SleepRules {
	/** 自分のベッドがこの距離以内なら歩いて行って寝る（ブロック）。 */
	public static final double OWN_BED_RANGE = 48.0;

	private SleepRules() {
	}

	/** オーバーワールドの夜（または雷雨）で、ベッドを持っているか近くに自分のベッドがあるとき true。ネザー・エンドでは常に false。 */
	public static boolean shouldSleep(FakeMember m) {
		if (!(m.level() instanceof ServerLevel l) || m.isPuddle() || m.isSleeping() || m.isDeadOrDying()) {
			return false;
		}
		if (!isSleepTime(l) || !policyOk(m, l)) {
			return false;
		}
		return Inv.count(m, ItemTags.BEDS) > 0 || ownBedNearby(m) != null;
	}

	/** 今の夜の日数（夜は同じ日数のまま 13000〜23000）。 */
	public static long today(ServerLevel l) {
		return Math.floorDiv(l.getOverworldClockTime(), 24000L);
	}

	/**
	 * 寝る方針: 夜は一晩1回だけ（印が今夜でなければ）。昼の雷雨は本物のプレイヤーが寝ているときだけ。
	 * ボットが寝ても夜は飛ばないので、毎晩朝まで止まらないようにする。
	 */
	static boolean policyOk(FakeMember m, ServerLevel l) {
		if (Observer.isNight(l)) {
			return m.getSleptDay() != today(l) || realPlayerSleeping(l);
		}
		return realPlayerSleeping(l);
	}

	/** 同じ次元で寝ている本物のプレイヤーがいるか。 */
	static boolean realPlayerSleeping(ServerLevel l) {
		for (net.minecraft.server.level.ServerPlayer p : l.players()) {
			if (p.isSleeping()) {
				return true;
			}
		}
		return false;
	}

	/** オーバーワールドで、夜か雷雨。 */
	static boolean isSleepTime(ServerLevel l) {
		return l.dimension().equals(Level.OVERWORLD) && (Observer.isNight(l) || l.isThundering());
	}

	/** 同じ次元の近くにある、まだベッドとして残っている自分のベッド（頭側）。無ければ null。 */
	static BlockPos ownBedNearby(FakeMember m) {
		BlockPos bed = m.getBedPos();
		if (bed == null || !m.level().dimension().equals(m.getBedDim())) {
			return null;
		}
		if (m.position().distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) > OWN_BED_RANGE * OWN_BED_RANGE) {
			return null;
		}
		return m.level().getBlockState(bed).getBlock() instanceof BedBlock ? bed : null;
	}
}
