package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 金インゴットをピグリンに投げて、落ちた物を拾う。1個あたり約6秒（ピグリンが品定めする時間）。 */
final class BarterSkill extends Skill {
	private static final int ADMIRE_WAIT = 135;

	private final int wantGold;
	private int thrown;
	private int cooldown;
	private int collectTicks;
	private int ticks;
	private Piglin piglin;

	BarterSkill(FakeMember o, SkillRunner r, int gold) {
		super(o, r);
		this.wantGold = Math.max(1, gold);
	}

	@Override
	String begin() {
		if (Inv.count(o, Items.GOLD_INGOT) <= 0) {
			return "できない: 金インゴットを持っていない";
		}
		piglin = find();
		if (piglin == null) {
			return "できない: 近くにピグリンがいない";
		}
		return null;
	}

	private Piglin find() {
		List<Piglin> list = SkillUtil.level(o).getEntitiesOfClass(Piglin.class, o.getBoundingBox().inflate(32, 12, 32),
				p -> p.isAlive());
		Piglin best = null;
		double bd = Double.MAX_VALUE;
		for (Piglin p : list) {
			double d = p.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}

	@Override
	void tick() {
		ticks++;
		if (cooldown > 0) {
			cooldown--;
		}
		if (ticks > 20 * 60 * 5) {
			finish("取引を切り上げた（金" + thrown + "個）", null);
			return;
		}
		boolean done = thrown >= wantGold || Inv.count(o, Items.GOLD_INGOT) <= 0;
		if (done && cooldown <= 0) {
			collect();
			return;
		}
		if (piglin == null || !piglin.isAlive()) {
			piglin = find();
			if (piglin == null) {
				finish("ピグリンがいなくなった（金" + thrown + "個）", null);
			}
			return;
		}
		double d = Math.sqrt(o.distanceToSqr(piglin));
		if (d > 4.5) {
			nav.go(piglin.position(), 3.5, 1.0);
			nav.tick();
			if (nav.failed()) {
				finish("ピグリンに近づけない（金" + thrown + "個）", null);
			}
			return;
		}
		nav.stop();
		Motion.face(o, piglin.getEyePosition());
		// UNVERIFIED: 品定め中（ADMIRING_ITEM）に重ねて投げると無駄になるので、その間は待つ
		if (cooldown <= 0 && !done && piglin.getArmPose() != PiglinArmPose.ADMIRING_ITEM) {
			throwGold();
		}
	}

	private void throwGold() {
		ServerLevel l = SkillUtil.level(o);
		if (!Inv.remove(o, Items.GOLD_INGOT, 1)) {
			return;
		}
		Vec3 from = o.getEyePosition().add(0, -0.3, 0);
		Vec3 to = piglin.position().add(0, 0.6, 0);
		Vec3 dir = to.subtract(from);
		double len = Math.max(0.1, dir.length());
		Vec3 v = new Vec3(dir.x / len * 0.3, 0.12, dir.z / len * 0.3);
		ItemEntity ie = new ItemEntity(l, from.x, from.y, from.z, new ItemStack(Items.GOLD_INGOT), v.x, v.y, v.z);
		ie.setThrower(o);
		ie.setPickUpDelay(20);
		l.addFreshEntity(ie);
		SkillUtil.swing(o);
		thrown++;
		cooldown = ADMIRE_WAIT;
	}

	/** 取引でもらった物を拾う。近くの落とし物がなくなったら終わり。 */
	private void collect() {
		collectTicks++;
		List<ItemEntity> items = SkillUtil.items(o, 14);
		if (items.isEmpty() || collectTicks > 200) {
			finish("取引した（金" + thrown + "個）", "ピグリンと取引した（金" + thrown + "個）");
			return;
		}
		nav.setVertTol(3.0);
		nav.go(items.get(0).position(), 0.6, 1.0);
		nav.tick();
		if (nav.failed()) {
			finish("取引した（金" + thrown + "個）", "ピグリンと取引した（金" + thrown + "個）");
		}
	}

	@Override
	String status() {
		return "ピグリンと取引している（金" + thrown + "/" + wantGold + "個）";
	}
}
