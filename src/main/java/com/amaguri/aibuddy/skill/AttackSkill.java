package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * 敵を殴る（attack）／獲物を狩って落とし物を拾う（hunt）。プレイヤー（PvP）は対象にしない。
 * 手に持つのは Inv.bestWeapon の実物だけ（無ければ素手）。戦い方は Combat と共通（クールダウン・クリティカル・盾・よけ）。
 */
final class AttackSkill extends Skill {
	private final String targetSpec;
	private final boolean hunt;
	private final int wanted;
	private EntityType<?> type; // null なら nearest_hostile
	private LivingEntity target;
	private int kills;
	private int idle;
	private int ticks;
	private int collectTicks;
	private final Combat.State cs;

	AttackSkill(FakeMember o, SkillRunner r, String target) {
		this(o, r, target, false, 1);
	}

	AttackSkill(FakeMember o, SkillRunner r, String target, boolean hunt, int count) {
		super(o, r);
		this.targetSpec = target == null ? "nearest_hostile" : target.trim();
		this.hunt = hunt;
		this.wanted = Math.max(1, count);
		this.cs = Combat.state(o);
	}

	@Override
	String begin() {
		if (!targetSpec.equalsIgnoreCase("nearest_hostile")) {
			if (SkillUtil.findPlayer(o, targetSpec) != null) {
				return "できない: プレイヤーは攻撃しない";
			}
			Identifier id = Identifier.tryParse(targetSpec.contains(":") ? targetSpec.toLowerCase() : "minecraft:" + targetSpec.toLowerCase());
			if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
				return "できない: 知らないエンティティ " + targetSpec;
			}
			type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
			if (type == EntityTypes.PLAYER) {
				return "できない: プレイヤーは攻撃しない";
			}
		}
		target = findTarget();
		if (target == null) {
			return "できない: 近くに" + label() + "がいない";
		}
		return null;
	}

	private String label() {
		return type == null ? "敵" : Names.entity(type);
	}

	private double range() {
		return hunt ? 64 : 24;
	}

	private LivingEntity findTarget() {
		double r = range();
		List<LivingEntity> list = SkillUtil.level(o).getEntitiesOfClass(LivingEntity.class,
				o.getBoundingBox().inflate(r, hunt ? 24 : 10, r), this::valid);
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (LivingEntity e : list) {
			double d = e.distanceToSqr(o);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	private boolean valid(LivingEntity e) {
		if (e == o || !e.isAlive() || e instanceof Player || e instanceof FakeMember) {
			return false;
		}
		if (e instanceof net.minecraft.world.entity.animal.Animal a && hunt && a.isBaby()) {
			return false;
		}
		return type == null ? e instanceof Enemy : e.getType() == type;
	}

	@Override
	void tick() {
		ticks++;
		Combat.coolDown(o, cs);
		if (collectTicks > 0) {
			collect();
			return;
		}
		if (target == null || !target.isAlive() || target.isRemoved()) {
			if (target != null && !target.isAlive()) {
				kills++;
				if (hunt && kills >= wanted) {
					collectTicks = 1;
					nav.stop();
					return;
				}
			}
			target = findTarget();
			if (target == null) {
				if (++idle > 40) {
					finishFight();
				}
				return;
			}
		}
		idle = 0;
		if (ticks > (hunt ? 6000 : 2400)) {
			finish("戦い続けて疲れた（" + kills + "体倒した）", null);
			return;
		}
		double d = Combat.distTo(o, target);
		Combat.equipBest(o, cs);
		if (d > 8 || !o.hasLineOfSight(target)) {
			// 遠い・見えない: 経路で近づく（ダッシュ）
			nav.go(target.position(), 2.0, 1.0);
			nav.tick();
			if (nav.failed()) {
				target = null;
				nav.stop();
			}
			return;
		}
		nav.stop();
		Motion.face(o, target.getEyePosition());
		if (d > Combat.REACH) {
			o.setSprinting(true);
			o.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), Motion.speedMod(o, true, 1.0));
			if (o.onGround() && target.getY() > o.getY() + 0.6) {
				o.getJumpControl().jump();
			}
			return;
		}
		// 間合い: ジャンプ攻撃（落下中に殴ってクリティカル）＋横によけ
		if (--cs.strafeTicks <= 0) {
			cs.strafeTicks = 12 + o.getRandom().nextInt(12);
			cs.strafeDir = o.getRandom().nextBoolean() ? 1 : -1;
		}
		boolean falling = !o.onGround() && o.getDeltaMovement().y < -0.05;
		if (cs.cooldown <= 0) {
			if (falling || cs.cooldown < -8) {
				Combat.hit(o, cs, target, falling);
			} else if (o.onGround()) {
				o.getJumpControl().jump();
			}
		} else if (cs.cooldown < 6 && o.onGround()) {
			o.getJumpControl().jump();
		}
		Combat.moveRel(o, d < 2.0 ? -0.5f : 0.2f, cs.strafeDir * 0.8f, true);
	}

	private void finishFight() {
		finish(kills > 0 ? "倒した（" + kills + "体）" : "敵がいなくなった", kills > 0 ? label() + "を" + kills + "体倒した" : null);
	}

	/** 狩りのあと、落とし物（肉・羊毛・パール・ブレイズロッド）を拾いに行く。 */
	private void collect() {
		collectTicks++;
		List<ItemEntity> items = SkillUtil.items(o, 12);
		if (items.isEmpty() || collectTicks > 160) {
			finishFight();
			return;
		}
		nav.go(items.get(0).position(), 0.6, 1.0);
		nav.setVertTol(3.0);
		nav.tick();
		if (nav.failed()) {
			finishFight();
		}
	}

	@Override
	String status() {
		return label() + (hunt ? "を狩っている（" : "と戦っている（") + kills + "体倒した）";
	}
}
