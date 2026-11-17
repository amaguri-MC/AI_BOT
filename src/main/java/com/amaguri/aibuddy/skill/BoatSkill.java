package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 水を渡る（boat{x,y,z}）。Nav が長い水面（24ブロック以上）を見つけたときも同じ Ride を使う。
 * 手順: 岸へ歩く → ボートを作る（板材5。無ければ原木2→板材）→ 水面に置く → 乗る →
 * 向きと速度を毎tick自前で与えて目標へ → 陸に着いたら降りてボートを回収。作れなければ呼び出し側が泳ぐ。
 */
final class BoatSkill extends Skill {
	private final Vec3 target;
	private int ticks;

	BoatSkill(FakeMember o, SkillRunner r, double x, double y, double z) {
		super(o, r);
		this.target = new Vec3(x, y, z);
	}

	@Override
	String begin() {
		nav.setBoatMinLength(8);
		nav.go(target, 1.5, 1.0);
		return null;
	}

	@Override
	void tick() {
		ticks++;
		nav.tick();
		if (nav.arrived()) {
			finish("着いた " + SkillUtil.pos(target), "目的地に着いた " + SkillUtil.pos(target));
		} else if (nav.failed() || ticks > 20 * 60 * 6) {
			finish("たどり着けなかった " + SkillUtil.pos(target), null);
		}
	}

	@Override
	String status() {
		return "水を渡って座標 " + SkillUtil.pos(target) + " へ（あと" + Math.round(nav.distance()) + "m）";
	}

	// ------------------------------------------------------------------

	/** ボートで渡る1回分。tick() を毎tick呼ぶ。finished() で終わり。 */
	static final class Ride {
		private enum S { WALK, PLACE, BOARD, SAIL, DONE }

		private static final double SPEED = 0.28; // 約5.6 ブロック/秒
		private static final float TURN = 14.0f;

		private final FakeMember o;
		private final Vec3 goal;
		private final Vec3 shore;
		private final BlockPos water0;
		private S s = S.WALK;
		private AbstractBoat boat;
		private int t;
		private float yaw;
		private Vec3 lastPos;
		private int slow;
		boolean ok = true;

		Ride(FakeMember o, Vec3 goal, Vec3 shore, BlockPos water0) {
			this.o = o;
			this.goal = goal;
			this.shore = shore;
			this.water0 = water0;
		}

		/** 材料があるか。無ければ「できない: …」。 */
		static String canPrepare(FakeMember o) {
			if (Inv.count(o, ItemTags.BOATS) > 0 || Inv.count(o, ItemTags.PLANKS) >= 5 || Inv.count(o, ItemTags.LOGS) >= 2) {
				return null;
			}
			return "できない: ボートの材料が無い（板材5）";
		}

		boolean finished() {
			return s == S.DONE;
		}

		boolean riding() {
			return s == S.SAIL || s == S.BOARD;
		}

		void abort() {
			if (s == S.DONE) {
				return;
			}
			recover();
			s = S.DONE;
		}

		void tick() {
			ServerLevel l = SkillUtil.level(o);
			t++;
			switch (s) {
				case WALK -> {
					if (o.isInWater() || SkillUtil.horiz(o.position(), shore) < 1.6) {
						s = S.PLACE;
					} else if (t > 400) {
						fail();
					} else {
						o.setSprinting(true);
						if (t % 20 == 1) {
							o.getNavigation().moveTo(shore.x, shore.y, shore.z, Motion.speedMod(o, true, 1.0));
						}
					}
				}
				case PLACE -> place(l);
				case BOARD -> {
					if (boat == null || !boat.isAlive()) {
						fail();
					} else if (o.startRiding(boat, true, false) || o.getVehicle() == boat) {
						yaw = boat.getYRot();
						lastPos = boat.position();
						s = S.SAIL;
					} else if (t > 60) {
						fail();
					}
				}
				case SAIL -> sail(l);
				default -> {
				}
			}
		}

		private void fail() {
			ok = false;
			abort();
		}

		private void place(ServerLevel l) {
			if (Inv.count(o, ItemTags.BOATS) > 0) {
				Inv.remove(o, ItemTags.BOATS, 1);
			} else {
				if (Inv.count(o, ItemTags.PLANKS) < 5 && Inv.count(o, ItemTags.LOGS) >= 2) {
					Inv.remove(o, ItemTags.LOGS, 2);
					Inv.add(o, new ItemStack(Items.OAK_PLANKS, 8));
				}
				if (!Inv.remove(o, ItemTags.PLANKS, 5)) {
					fail();
					return;
				}
			}
			double x = water0.getX() + 0.5;
			double z = water0.getZ() + 0.5;
			double y = water0.getY();
			BlockPos p = water0;
			for (int i = 0; i < 6 && l.getBlockState(p.above()).is(Blocks.WATER); i++) {
				p = p.above();
			}
			y = p.getY() + l.getFluidState(p).getHeight(l, p) - 0.1;
			float face = Mth.wrapDegrees((float) (Mth.atan2(goal.z - z, goal.x - x) * Mth.RAD_TO_DEG) - 90.0f);
			// UNVERIFIED: Boat のコンストラクタ (EntityType, Level, Supplier<Item>) と addFreshEntity での生成
			Boat b = new Boat(EntityTypes.OAK_BOAT, l, () -> Items.OAK_BOAT);
			b.setPos(x, y, z);
			b.setYRot(face);
			l.addFreshEntity(b);
			boat = b;
			SkillUtil.swing(o);
			l.playSound(null, b.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.PLAYERS, 1.0f, 1.0f);
			s = S.BOARD;
			t = 0;
		}

		private void sail(ServerLevel l) {
			if (boat == null || !boat.isAlive() || o.getVehicle() != boat) {
				fail();
				return;
			}
			Vec3 bp = boat.position();
			double dx = goal.x - bp.x;
			double dz = goal.z - bp.z;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist < 2.5) {
				land(l, null);
				return;
			}
			float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
			// 前方の水の有無で進路を選ぶ（まっすぐ → 左右へ振る）
			float[] offs = { 0, 25, -25, 50, -50, 80, -80, 120, -120 };
			float chosen = Float.NaN;
			for (float off : offs) {
				float a = want + off;
				if (clear(l, bp, a, 6.0)) {
					chosen = a;
					break;
				}
				if (off == 0) {
					// 正面が陸なら、立てる場所があれば上陸
					Vec3 st = standAhead(l, bp, a);
					if (st != null) {
						land(l, st);
						return;
					}
				}
			}
			if (Float.isNaN(chosen)) {
				Vec3 st = standAhead(l, bp, want);
				land(l, st);
				return;
			}
			float diff = Mth.wrapDegrees(chosen - yaw);
			yaw += Mth.clamp(diff, -TURN, TURN);
			Vec3 dir = Motion.dirOfYaw(yaw);
			boat.setYRot(yaw);
			o.setYRot(yaw);
			o.setYHeadRot(yaw);
			o.setYBodyRot(yaw);
			double sp = Math.abs(diff) > 60 ? SPEED * 0.4 : SPEED;
			boat.setDeltaMovement(dir.x * sp, boat.getDeltaMovement().y, dir.z * sp);
			boat.setPaddleState(true, true);
			// UNVERIFIED: mob 乗客だと Boat の本体が動かないことがある。進んでいなければ自分で位置を進める
			if (t % 10 == 0) {
				double moved = SkillUtil.horiz(bp, lastPos);
				slow = moved < sp * 10 * 0.3 ? slow + 1 : 0;
				lastPos = bp;
			}
			if (slow >= 2) {
				boat.setPos(bp.x + dir.x * sp, bp.y, bp.z + dir.z * sp);
			}
		}

		/** bp から角度 a の方向 dist ブロック先まで、水が続くか（2ブロックごと）。 */
		private boolean clear(ServerLevel l, Vec3 bp, float a, double dist) {
			Vec3 d = Motion.dirOfYaw(a);
			for (double k = 2.0; k <= dist; k += 2.0) {
				if (!Motion.waterColumn(l, bp.x + d.x * k, bp.z + d.z * k, bp.y)) {
					return false;
				}
			}
			return true;
		}

		/** 正面 2〜4 ブロックに歩いて立てる陸があれば、その立ち位置。 */
		private Vec3 standAhead(ServerLevel l, Vec3 bp, float a) {
			Vec3 d = Motion.dirOfYaw(a);
			for (double k = 2.0; k <= 4.5; k += 0.5) {
				Vec3 st = standAt(l, bp.x + d.x * k, bp.z + d.z * k, bp.y);
				if (st != null) {
					return st;
				}
			}
			return null;
		}

		private static Vec3 standAt(ServerLevel l, double x, double z, double refY) {
			int bx = Mth.floor(x);
			int bz = Mth.floor(z);
			for (int y = Mth.floor(refY) + 2; y >= Mth.floor(refY) - 3; y--) {
				BlockPos floor = new BlockPos(bx, y, bz);
				if (l.getBlockState(floor).blocksMotion() && l.getFluidState(floor).isEmpty()
						&& !l.getBlockState(floor.above()).blocksMotion() && !l.getBlockState(floor.above(2)).blocksMotion()
						&& l.getFluidState(floor.above()).isEmpty()) {
					return new Vec3(bx + 0.5, y + 1.0, bz + 0.5);
				}
			}
			return null;
		}

		private void land(ServerLevel l, Vec3 stand) {
			o.stopRiding();
			if (stand != null) {
				o.setPos(stand.x, stand.y, stand.z);
				o.setDeltaMovement(Vec3.ZERO);
			}
			recover();
			s = S.DONE;
		}

		/** ボートを壊して拾う（降りたあと / 失敗したとき）。 */
		private void recover() {
			if (o.getVehicle() != null && o.getVehicle() == boat) {
				o.stopRiding();
			}
			if (boat != null && boat.isAlive()) {
				Inv.add(o, new ItemStack(Items.OAK_BOAT));
				boat.discard();
				SkillUtil.level(o).playSound(null, o.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.0f);
			}
			boat = null;
		}
	}
}
