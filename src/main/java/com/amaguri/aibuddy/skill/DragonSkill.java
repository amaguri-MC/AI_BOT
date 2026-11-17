package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * エンドラ戦。クリスタルを壊す → 止まり木に降りた竜の頭を殴る → ブレス（雲）・火の玉をよける。
 * 倒したら event「エンドラ倒した」。
 *
 * クリスタル: 弓と矢があれば離れた所から射る（爆発は半径12に届くので近づかない）。
 * 無ければ塔のそばで足場を積んで上がり、盾を構えて殴る（爆風を盾で受け、落ちたら水バケツ着地）。
 * UNVERIFIED: 塔・檻の寸法、盾が爆風を受けるか、竜の止まり木位置は実機で調整が必要。
 */
final class DragonSkill extends Skill {
	private enum Phase { CRYSTALS, DRAGON }

	private static final int MAX_TICKS = 20 * 60 * 30;

	private Phase phase = Phase.CRYSTALS;
	private int ticks;
	private int shotCd;
	private int draw;
	private int shots;
	private EndCrystal crystal;
	private final Combat.State cs;
	private final Motion.Pillar pillar = new Motion.Pillar();
	private int crystalsBroken;
	private int sideCd;
	private int sideDir = 1;

	DragonSkill(FakeMember o, SkillRunner r) {
		super(o, r);
		this.cs = Combat.state(o);
	}

	@Override
	String begin() {
		if (!SkillUtil.inDimension(o, Level.END)) {
			return "できない: エンドにいない";
		}
		return null;
	}

	@Override
	void tick() {
		ticks++;
		Combat.coolDown(o, cs);
		ServerLevel l = SkillUtil.level(o);
		if (ticks > MAX_TICKS) {
			finish("エンドラ戦を切り上げた", null);
			return;
		}
		List<? extends EnderDragon> dragons = l.getDragons();
		EnderDragon dragon = null;
		boolean dying = false;
		for (EnderDragon d : dragons) {
			if (d.isAlive() && d.getHealth() > 0 && d.dragonDeathTime == 0) {
				dragon = d;
			} else {
				dying = true;
			}
		}
		if (dragon == null && (dying || ticks > 60)) {
			finish("エンドラを倒した", "エンドラ倒した");
			return;
		}
		if (dragon == null) {
			return;
		}
		Combat.equipBest(o, cs);
		Vec3 threatPos = dragon.position();
		if (dragonThreat(l, dragon) != null) {
			threatPos = dragonThreat(l, dragon);
		}
		if (dodge(l, dragon)) {
			return;
		}
		if (Combat.lowHealthRoutine(o, dragon.distanceToSqr(o) < 30 * 30 ? threatPos : null)) {
			return;
		}
		List<EndCrystal> crystals = l.getEntitiesOfClass(EndCrystal.class, o.getBoundingBox().inflate(160, 200, 160),
				c -> c.isAlive());
		if (phase == Phase.CRYSTALS && crystals.isEmpty()) {
			phase = Phase.DRAGON;
			nav.stop();
			o.postEvent("クリスタルを全部壊した");
		}
		if (phase == Phase.CRYSTALS) {
			crystalTick(l, crystals);
		} else {
			dragonTick(l, dragon);
		}
	}

	// ---- よける ----

	/** 竜のブレス雲・火の玉・突進から離れる位置。無ければ null。 */
	private Vec3 dragonThreat(ServerLevel l, EnderDragon d) {
		AABB box = o.getBoundingBox().inflate(7, 4, 7);
		for (AreaEffectCloud c : l.getEntitiesOfClass(AreaEffectCloud.class, box, e -> true)) {
			if (c.position().distanceTo(o.position()) < c.getRadius() + 2.0) {
				return c.position();
			}
		}
		for (DragonFireball f : l.getEntitiesOfClass(DragonFireball.class, o.getBoundingBox().inflate(9, 6, 9), e -> true)) {
			return f.position();
		}
		return null;
	}

	private boolean dodge(ServerLevel l, EnderDragon d) {
		Vec3 danger = dragonThreat(l, d);
		if (danger == null) {
			EnderDragonPhase<?> ph = d.getPhaseManager().getCurrentPhase().getPhase();
			boolean charging = ph == EnderDragonPhase.CHARGING_PLAYER || ph == EnderDragonPhase.STRAFE_PLAYER;
			if (!(charging && d.distanceToSqr(o) < 16 * 16)) {
				return false;
			}
			// 突進: 竜の進行方向と直角に横へ跳ぶ
			Vec3 mv = d.getDeltaMovement();
			Vec3 side = new Vec3(-mv.z, 0, mv.x);
			if (side.lengthSqr() < 1e-4) {
				return false;
			}
			if (--sideCd <= 0) {
				sideCd = 30;
				sideDir = o.getRandom().nextBoolean() ? 1 : -1;
			}
			Vec3 to = o.position().add(side.normalize().scale(6 * sideDir));
			sprintTo(to);
			return true;
		}
		Vec3 away = o.position().subtract(danger);
		Vec3 h = new Vec3(away.x, 0, away.z);
		h = h.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : h.normalize();
		sprintTo(o.position().add(h.scale(7)));
		return true;
	}

	private void sprintTo(Vec3 to) {
		nav.stop();
		o.setSprinting(true);
		o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
		if (o.onGround() && o.tickCount % 11 == 0) {
			o.getJumpControl().jump();
		}
		Motion.lavaBrake(o);
	}

	// ---- クリスタル ----

	private void crystalTick(ServerLevel l, List<EndCrystal> crystals) {
		if (crystal == null || !crystal.isAlive()) {
			if (crystal != null) {
				crystalsBroken++;
				shots = 0;
				pillar.reset();
			}
			crystal = null;
			double bd = Double.MAX_VALUE;
			for (EndCrystal c : crystals) {
				double d = SkillUtil.horiz(o.position(), c.position());
				if (d < bd) {
					bd = d;
					crystal = c;
				}
			}
			if (crystal == null) {
				return;
			}
		}
		boolean bow = Inv.count(o, Items.BOW) > 0 && Inv.count(o, Items.ARROW) > 0;
		if (bow && shots < 6) {
			shootCrystal(l);
		} else {
			punchCrystal(l);
		}
	}

	private void shootCrystal(ServerLevel l) {
		Vec3 cp = crystal.position().add(0, 1.0, 0);
		double d = o.position().distanceTo(cp);
		boolean los = o.hasLineOfSight(crystal);
		if (shotCd > 0) {
			shotCd--;
		}
		if (d < 16 || d > 48 || !los) {
			// 射てる距離（18ブロック）に回り込む
			Vec3 away = o.position().subtract(crystal.position());
			Vec3 h = new Vec3(away.x, 0, away.z);
			h = h.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : h.normalize();
			if (!los && d >= 16) {
				h = new Vec3(-h.z, 0, h.x); // 見えないなら周回して視線を探す
			}
			Vec3 spot = SkillUtil.snap(o, crystal.getX() + h.x * 20, crystal.getZ() + h.z * 20, o.getY());
			nav.go(spot, 2.0, 1.0);
			nav.tick();
			draw = 0;
			if (nav.failed()) {
				shots = 99;
			}
			return;
		}
		nav.stop();
		Motion.face(o, cp);
		if (draw == 0) {
			if (shotCd > 0) {
				return;
			}
			o.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, firstOf(Items.BOW));
			o.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
		}
		if (++draw >= 22) {
			o.stopUsingItem();
			fireArrow(l, cp);
			draw = 0;
			shotCd = 24;
			shots++;
		}
	}

	private ItemStack firstOf(net.minecraft.world.item.Item it) {
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack s = o.getInventory().getItem(i);
			if (s.is(it)) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	/** 重力0.05・空気抵抗0.99・初速3.0 の矢が目標に一番近づく仰角を総当たりで探して撃つ。 */
	private void fireArrow(ServerLevel l, Vec3 target) {
		ItemStack bow = firstOf(Items.BOW);
		if (bow.isEmpty() || !Inv.remove(o, Items.ARROW, 1)) {
			return;
		}
		Vec3 eye = o.getEyePosition();
		double dx = target.x - eye.x;
		double dz = target.z - eye.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		double bestPitch = 0;
		double bestErr = Double.MAX_VALUE;
		double hd = Math.sqrt(dx * dx + dz * dz);
		for (double pitch = -80; pitch <= 80; pitch += 0.5) {
			double pr = Math.toRadians(pitch);
			double vh = 3.0 * Math.cos(pr);
			double vy = -3.0 * Math.sin(pr);
			double x = 0;
			double y = 0;
			double err = Double.MAX_VALUE;
			for (int t = 0; t < 100; t++) {
				x += vh;
				y += vy;
				vh *= 0.99;
				vy = vy * 0.99 - 0.05;
				double e = Math.hypot(x - hd, y - (target.y - eye.y));
				err = Math.min(err, e);
				if (x > hd + 4) {
					break;
				}
			}
			if (err < bestErr) {
				bestErr = err;
				bestPitch = pitch;
			}
		}
		o.setYRot(yaw);
		o.setXRot((float) bestPitch);
		o.setYHeadRot(yaw);
		Arrow arrow = new Arrow(l, o, new ItemStack(Items.ARROW), bow);
		arrow.shootFromRotation(o, (float) bestPitch, yaw, 0.0f, 3.0f, 0.5f);
		l.addFreshEntity(arrow);
		l.playSound(null, o.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f);
		Inv.damageTool(o, bow, 1);
	}

	/** 弓が無い（または当たらない）とき: 塔のそばで足場を積み、盾を構えて殴る。 */
	private void punchCrystal(ServerLevel l) {
		Vec3 cp = crystal.position();
		double hd = SkillUtil.horiz(o.position(), cp);
		double dy = cp.y - o.getY();
		if (Combat.hasShield(o)) {
			Combat.setShield(o, cs, true);
		}
		if (dy > 1.0 && hd < 7.5) {
			// 塔の脇から上へ
			nav.stop();
			int r = pillar.tick(o, digger);
			if (r == Motion.PILLAR_NO_BLOCK || r == Motion.PILLAR_BLOCKED) {
				finish("できない: クリスタルに届かない（足場が無い）", null);
			}
			return;
		}
		if (hd > 3.4) {
			// 檻の鉄格子は掘って通る
			BlockPos bars = barsToward(l, cp);
			if (bars != null && hd < 6) {
				if (!digger.active() && !digger.begin(bars)) {
					bars = null;
				}
				if (digger.active()) {
					digger.tick();
					return;
				}
			}
			nav.go(new Vec3(cp.x, cp.y, cp.z), 2.8, 1.0);
			nav.setVertTol(256);
			nav.tick();
			if (nav.failed()) {
				finish("できない: クリスタルへの道が無い", null);
			}
			return;
		}
		nav.stop();
		Motion.face(o, cp);
		Combat.setShield(o, cs, cs.cooldown > 0);
		if (cs.cooldown <= 0) {
			Combat.setShield(o, cs, false);
			Combat.hit(o, cs, crystal, false);
		}
	}

	private BlockPos barsToward(ServerLevel l, Vec3 cp) {
		Vec3 eye = o.getEyePosition();
		Vec3 d = cp.subtract(eye);
		double len = d.length();
		if (len < 0.1) {
			return null;
		}
		for (double t = 0.5; t < len; t += 0.5) {
			Vec3 p = eye.add(d.scale(t / len));
			BlockPos bp = BlockPos.containing(p);
			if (l.getBlockState(bp).is(net.minecraft.world.level.block.Blocks.IRON_BARS)) {
				return bp;
			}
		}
		return null;
	}

	// ---- 竜 ----

	private void dragonTick(ServerLevel l, EnderDragon dragon) {
		DragonPhaseInstance ph = dragon.getPhaseManager().getCurrentPhase();
		boolean perched = ph.isSitting() || ph.getPhase() == EnderDragonPhase.LANDING;
		Vec3 head = dragon.head.position();
		if (perched) {
			double d = Combat.distTo(o, dragon.head);
			Combat.setShield(o, cs, false);
			if (d > Combat.REACH + 0.3) {
				nav.go(new Vec3(head.x, o.getY(), head.z), 2.2, 1.0);
				nav.setVertTol(6);
				nav.tick();
				return;
			}
			nav.stop();
			Motion.face(o, head);
			boolean falling = !o.onGround() && o.getDeltaMovement().y < -0.05;
			if (cs.cooldown <= 0) {
				if (falling || cs.cooldown < -8) {
					Combat.hit(o, cs, dragon.head, falling);
				} else if (o.onGround()) {
					o.getJumpControl().jump();
				}
			}
			Combat.moveRel(o, 0.0f, (o.tickCount / 20 % 2 == 0 ? 1 : -1) * 0.4f, false);
			return;
		}
		// 飛んでいる間: 弓があれば撃つ。無ければ噴水の脇で待つ（近くを通ったら殴る）
		BlockPos origin = dragon.getFightOrigin();
		Vec3 wait = SkillUtil.snap(o, origin.getX() + 8.5, origin.getZ() + 0.5, o.getY());
		double dd = Combat.distTo(o, dragon.head);
		if (dd <= Combat.REACH) {
			Motion.face(o, head);
			if (cs.cooldown <= 0) {
				Combat.hit(o, cs, dragon.head, false);
			}
			return;
		}
		boolean bow = Inv.count(o, Items.BOW) > 0 && Inv.count(o, Items.ARROW) > 0;
		double dist = o.position().distanceTo(head);
		if (bow && dist > 12 && dist < 60 && o.hasLineOfSight(dragon)) {
			nav.stop();
			shotCd = Math.max(0, shotCd - 1);
			Motion.face(o, head);
			if (draw == 0 && shotCd == 0) {
				o.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, firstOf(Items.BOW));
				o.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
				draw = 1;
			} else if (draw > 0 && ++draw >= 22) {
				o.stopUsingItem();
				// 動く的なので少し先を狙う
				fireArrow(l, head.add(dragon.getDeltaMovement().scale(Math.min(25, dist / 2.5))));
				draw = 0;
				shotCd = 30;
			}
			return;
		}
		if (SkillUtil.horiz(o.position(), wait) > 3) {
			nav.go(wait, 2.5, 1.0);
			nav.tick();
		} else {
			nav.stop();
			Motion.face(o, head);
		}
	}

	@Override
	void onStop() {
		super.onStop();
		o.stopUsingItem();
		Combat.setShield(o, cs, false);
	}

	@Override
	String status() {
		return phase == Phase.CRYSTALS ? "エンドラ戦: クリスタルを壊している（" + crystalsBroken + "個壊した）" : "エンドラ戦: 竜と戦っている";
	}
}
