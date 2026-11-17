package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/** 敵を殴る。プレイヤー（PvP）は対象にしない。 */
final class AttackSkill extends Skill {
	private final String targetSpec;
	private EntityType<?> type; // null なら nearest_hostile
	private LivingEntity target;
	private int cooldown;
	private int kills;
	private int idle;
	private int ticks;

	AttackSkill(FakeMember o, SkillRunner r, String target) {
		super(o, r);
		this.targetSpec = target == null ? "nearest_hostile" : target.trim();
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
		if (o.getMainHandItem().isEmpty()) {
			runner.hold(Items.DIAMOND_SWORD);
		}
		return null;
	}

	private String label() {
		return type == null ? "敵" : Names.entity(type);
	}

	private LivingEntity findTarget() {
		List<LivingEntity> list = SkillUtil.level(o).getEntitiesOfClass(LivingEntity.class,
				o.getBoundingBox().inflate(24, 10, 24), this::valid);
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
		return type == null ? e instanceof Enemy : e.getType() == type;
	}

	@Override
	void tick() {
		ticks++;
		if (cooldown > 0) {
			cooldown--;
		}
		if (target == null || !target.isAlive() || target.isRemoved()) {
			if (target != null && !target.isAlive()) {
				kills++;
			}
			target = findTarget();
			if (target == null) {
				if (++idle > 40) {
					finish(kills > 0 ? "倒した（" + kills + "体）" : "敵がいなくなった", kills > 0 ? label() + "を" + kills + "体倒した" : null);
				}
				return;
			}
		}
		idle = 0;
		if (ticks > 2400) {
			finish("戦い続けて疲れた（" + kills + "体倒した）", null);
			return;
		}
		double d = Math.sqrt(o.distanceToSqr(target));
		SkillUtil.lookAt(o, target.getEyePosition());
		if (d > 2.8) {
			nav.setSprint(true);
			nav.go(target.position(), 2.0, 1.25);
			nav.tick();
			if (nav.failed()) {
				target = null;
				nav.stop();
			}
			return;
		}
		nav.stop();
		o.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), d > 1.6 ? 1.0 : 0.0);
		if (cooldown == 0) {
			cooldown = 10;
			SkillUtil.swing(o);
			// UNVERIFIED: Mob.doHurtTarget は ATTACK_DAMAGE 属性が必要（FakeMember 側で設定される前提）
			o.doHurtTarget((ServerLevel) o.level(), target);
		}
	}

	@Override
	String status() {
		return label() + "と戦っている（" + kills + "体倒した）";
	}
}
