package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 足を置く先の共通の関門。Combat / Evade / Nav / スキルのどの移動も最後は MoveControl・JumpControl を通るので、
 * FakeMember はこの2つを使う。歩く入力（前後・左右）の向きの先が溶岩・火（または10段以上の落下）なら、その入力を消して
 * その向きの勢いも止める。跳ぶ前は着地しそうな所に溶岩・火が無いか見る。自分で溶岩に入る動きはどの経路でもしない。
 */
public final class SafeFooting {
	private SafeFooting() {
	}

	/** 10段下まで床が無ければ大きな落下として危険扱い（崖の2〜3段の下りは今まで通り許す）。 */
	private static final int FALL_LIMIT = 10;

	static boolean lethal(BlockState s) {
		return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
	}

	/** (x,z) に足を置いたら危ないか: 足・頭の高さが溶岩/火、落ちた先の最初の床が溶岩/火、または FALL_LIMIT 段下まで床が無い。 */
	static boolean deadlyAt(ServerLevel l, double x, double z, double y) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int feet = Mth.floor(y + 1.0E-3);
		BlockPos fp = new BlockPos(bx, feet, bz);
		if (!l.hasChunkAt(fp)) {
			return false;
		}
		if (lethal(l.getBlockState(fp.above()))) {
			return true;
		}
		for (int d = 0; d <= FALL_LIMIT; d++) {
			BlockPos q = fp.below(d);
			if (q.getY() < l.getMinY()) {
				return true;
			}
			BlockState st = l.getBlockState(q);
			if (lethal(st)) {
				return true;
			}
			if (st.blocksMotion() || st.is(Blocks.WATER)) {
				return false;
			}
		}
		return true;
	}

	/** 今の位置から水平の向き (dx,dz)（単位）へ、近い所と勢いで滑る先までのどこかが危ないか。 */
	static boolean deadlyAhead(FakeMember o, double dx, double dz) {
		ServerLevel l = SkillUtil.level(o);
		Vec3 v = o.getDeltaMovement();
		double sp = Math.max(0, v.x * dx + v.z * dz);
		double far = 0.8 + sp * 2.5;
		for (double k : new double[] { 0.45, 0.8, far }) {
			if (deadlyAt(l, o.getX() + dx * k, o.getZ() + dz * k, o.getY())) {
				return true;
			}
		}
		return false;
	}

	/** 関門を素通りしてよい時: 世界にいない・もう溶岩の中（出るための動きは止めない）・乗り物の上。 */
	private static boolean exempt(FakeMember o) {
		return !(o.level() instanceof ServerLevel) || o.isInLava() || o.isPassenger() || o.isNoGravity();
	}

	/** 移動の関門。MoveControl が決めた入力（前後 zza・左右 xxa）を、体の向きで世界の向きにして確かめる。 */
	public static final class Move extends MoveControl<FakeMember> {
		public Move(FakeMember mob) {
			super(mob);
		}

		@Override
		public void tick() {
			super.tick();
			FakeMember o = this.mob;
			if (exempt(o)) {
				return;
			}
			float fwd = o.zza;
			float right = o.xxa;
			if (Math.abs(fwd) < 1.0E-3 && Math.abs(right) < 1.0E-3) {
				return;
			}
			// LivingEntity.moveRelative と同じ回し方（入力 x=xxa, z=zza を yRot で回す）
			double r = Math.toRadians(o.getYRot());
			double sin = Math.sin(r);
			double cos = Math.cos(r);
			double wx = right * cos - fwd * sin;
			double wz = fwd * cos + right * sin;
			double len = Math.sqrt(wx * wx + wz * wz);
			if (len < 1.0E-4) {
				return;
			}
			wx /= len;
			wz /= len;
			if (!deadlyAhead(o, wx, wz)) {
				return;
			}
			// 危ない向きの入力を消し、その向きの勢いも止める（踏み出さない）
			o.setSpeed(0.0f);
			o.setZza(0.0f);
			o.setXxa(0.0f);
			o.setSprinting(false);
			this.operation = MoveControl.Operation.WAIT;
			Vec3 v = o.getDeltaMovement();
			double toward = v.x * wx + v.z * wz;
			if (toward > 0 && o.onGround()) {
				o.setDeltaMovement(v.x - wx * toward, v.y, v.z - wz * toward);
			}
			SafeFooting.blocked(o, wx, wz);
		}
	}

	/** 跳ぶ前の関門: 動いている向き（止まっていれば体の向き）の着地しそうな所に溶岩・火・大きな落下が無いか。 */
	public static final class Jump extends JumpControl {
		private final FakeMember o;

		public Jump(FakeMember mob) {
			super(mob);
			this.o = mob;
		}

		@Override
		public void tick() {
			if (this.jump && !exempt(o) && !safeJump(o)) {
				this.jump = false;
			}
			super.tick();
		}
	}

	static boolean safeJump(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		Vec3 v = o.getDeltaMovement();
		double h = Math.sqrt(v.x * v.x + v.z * v.z);
		if (h < 0.03) {
			return true; // その場で跳ぶ（柱を積む・段差を登る）のは同じ所に降りる
		}
		double dx = v.x / h;
		double dz = v.z / h;
		for (double k : new double[] { 0.6, 1.2, 1.8, 2.6 }) {
			if (deadlyAt(l, o.getX() + dx * k, o.getZ() + dz * k, o.getY())) {
				return false;
			}
		}
		return true;
	}

	/** 関門が止めた時の記録（調査用。TRACE） */
	static void blocked(FakeMember o, double wx, double wz) {
		if (o.entityTags().contains("lavatrace")) {
			Combat.state(o).gate += " GATE(" + String.format("%.2f,%.2f", wx, wz) + ")";
		}
	}
}
