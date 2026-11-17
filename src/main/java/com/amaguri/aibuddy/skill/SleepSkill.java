package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ベッドで寝る（スキル名 sleep）。オーバーワールドの夜・雷雨だけ。ネザー・エンドでは絶対に置かない。
 * 近くに自分のベッドがあればそこへ歩いて寝る。無ければベッドを持っているときだけ、安全な平地に置いて寝る。
 * 寝たらそこをリスポーン地点にする。朝になる・近くに敵が来る・殴られたら起きる。
 * 入力 pickup=true なら起きたあと置いたベッドを回収する（次の拠点が遠いとき用。既定は置きっぱなしでリスポーン地点を残す）。
 */
final class SleepSkill extends Skill {
	private static final int WALK_TIMEOUT = 20 * 40;
	private static final double ENEMY_RANGE = 8.0;
	private static final double SLEEP_REACH = 2.0;

	private final boolean pickup;
	private BlockPos head;
	private boolean placedByUs;
	private ItemStack bedItem = ItemStack.EMPTY;
	private int ticks;
	private boolean slept;

	SleepSkill(FakeMember o, SkillRunner r, boolean pickup) {
		super(o, r);
		this.pickup = pickup;
	}

	@Override
	String begin() {
		if (!(o.level() instanceof ServerLevel l)) {
			return "できない: まだ世界にいない";
		}
		if (!l.dimension().equals(Level.OVERWORLD)) {
			return "できない: オーバーワールドでしか寝ない";
		}
		if (!SleepRules.isSleepTime(l)) {
			return "できない: まだ寝る時間じゃない";
		}
		if (enemyNear(l)) {
			return "できない: 近くに敵がいる";
		}
		head = SleepRules.ownBedNearby(o);
		if (head != null) {
			return null;
		}
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack st = o.getInventory().getItem(i);
			if (!st.isEmpty() && st.is(ItemTags.BEDS) && st.getItem() instanceof BlockItem) {
				bedItem = st.copyWithCount(1);
				break;
			}
		}
		if (bedItem.isEmpty()) {
			return "できない: ベッドが無い";
		}
		if (!placeBed(l)) {
			return "できない: ベッドを置ける平地が近くに無い";
		}
		return null;
	}

	/** 足元の近くの、頭側・足側とも空気で床が固く頭上が空いている場所にベッドを置く。 */
	private boolean placeBed(ServerLevel l) {
		BlockPos base = o.blockPosition();
		BlockPos bestFoot = null;
		Direction bestDir = null;
		double best = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				BlockPos foot = base.offset(dx, 0, dz);
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos hd = foot.relative(d);
					if (spotOk(l, foot) && spotOk(l, hd)) {
						double dist = foot.distSqr(base);
						if (dist < best) {
							best = dist;
							bestFoot = foot;
							bestDir = d;
						}
					}
				}
			}
		}
		if (bestFoot == null) {
			return false;
		}
		BlockState bed = ((BlockItem) bedItem.getItem()).getBlock().defaultBlockState();
		BlockPos hd = bestFoot.relative(bestDir);
		l.setBlock(bestFoot, bed.setValue(BedBlock.FACING, bestDir).setValue(BedBlock.PART, BedPart.FOOT), 3);
		l.setBlock(hd, bed.setValue(BedBlock.FACING, bestDir).setValue(BedBlock.PART, BedPart.HEAD), 3);
		Inv.remove(o, bedItem.getItem(), 1);
		head = hd;
		placedByUs = true;
		SkillUtil.swing(o);
		return true;
	}

	private static boolean spotOk(ServerLevel l, BlockPos p) {
		BlockState here = l.getBlockState(p);
		BlockState above = l.getBlockState(p.above());
		BlockPos floor = p.below();
		return (here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty()))
				&& above.isAir()
				&& l.getBlockState(floor).isFaceSturdy(l, floor, Direction.UP)
				&& l.getBlockState(floor).getFluidState().isEmpty();
	}

	private boolean enemyNear(ServerLevel l) {
		AABB box = o.getBoundingBox().inflate(ENEMY_RANGE);
		return !l.getEntitiesOfClass(Monster.class, box, m -> m.isAlive()).isEmpty();
	}

	@Override
	void tick() {
		ticks++;
		if (!(o.level() instanceof ServerLevel l)) {
			finish("寝られなかった", null);
			return;
		}
		if (o.isSleeping()) {
			slept = true;
			if (!SleepRules.isSleepTime(l)) {
				wake(l, "朝になって起きた");
			} else if (enemyNear(l)) {
				wake(l, "敵が近づいて起きた");
			}
			return;
		}
		if (slept) {
			// 殴られるなどで寝床から離れた
			finish("起きた", null);
			return;
		}
		if (!SleepRules.isSleepTime(l)) {
			finish("寝る時間が終わった", null);
			return;
		}
		if (enemyNear(l)) {
			finish("敵が近くにいて寝られない", null);
			return;
		}
		if (!(l.getBlockState(head).getBlock() instanceof BedBlock)) {
			finish("ベッドが無くなっていた", null);
			return;
		}
		Vec3 target = Vec3.atBottomCenterOf(head);
		if (o.position().distanceTo(target) > SLEEP_REACH) {
			nav.go(target, SLEEP_REACH);
			nav.tick();
			if (ticks > WALK_TIMEOUT || nav.failed()) {
				finish("ベッドまで行けなかった", null);
			}
			return;
		}
		nav.stop();
		o.startSleeping(head); // UNVERIFIED: LivingEntity.startSleeping が位置合わせとベッドの OCCUPIED を面倒みる想定（gametest で確認）
		if (!o.isSleeping()) {
			finish("寝られなかった", null);
			return;
		}
		o.setBed(head, o.level().dimension());
		o.postEvent("ベッドで寝た（ここがリスポーン地点）");
	}

	private void wake(ServerLevel l, String why) {
		o.stopSleeping();
		if (pickup && placedByUs) {
			collectBed(l);
		}
		finish(why, null);
	}

	private void collectBed(ServerLevel l) {
		BlockState s = l.getBlockState(head);
		if (!(s.getBlock() instanceof BedBlock)) {
			return;
		}
		BlockPos foot = head.relative(BedBlock.getConnectedDirection(s));
		Inv.add(o, new ItemStack(s.getBlock().asItem()));
		l.removeBlock(head, false);
		l.removeBlock(foot, false);
		o.setBed(null, null);
	}

	@Override
	String status() {
		return o.isSleeping() ? "ベッドで寝ている" : "寝る準備をしている";
	}

	@Override
	void onStop() {
		super.onStop();
		if (o.isSleeping()) {
			o.stopSleeping();
		}
	}
}
