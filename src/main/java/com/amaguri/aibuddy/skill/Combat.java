package com.amaguri.aibuddy.skill;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 反射の戦闘。SkillRunner.tick が毎tick呼ぶ。脅威が近いときだけ fighting になり、
 * そのあいだ今のスキルは一時停止する想定（SkillRunner 側で isFighting を見て current.tick を飛ばす）。
 * 落下の水バケツ着地もここで毎tick見張る（スキルが何であっても働く）。
 *
 * 戦い方: 一番強い武器に持ち替え / 0.6秒(12tick)間隔 / ジャンプ攻撃で1.5倍のクリティカル /
 * 盾（持っていれば）をスケルトン・クリーパー・ブレイズに構える / 横に動いてよける / 体力8以下で下がって食べる。
 */
final class Combat {
	private Combat() {
	}

	static final int COOLDOWN = 12;
	static final int AXE_COOLDOWN = 20;
	static final double REACH = 3.0;
	private static final float LOW_HP = 8.0f;
	private static final float RECOVER_HP = 14.0f;

	static final class State {
		int cooldown;
		int stamp = -1;
		boolean fighting;
		int calm;
		LivingEntity target;
		int strafeDir = 1;
		int strafeTicks;
		int backTicks;
		boolean shieldUp;
		boolean retreating;
		int eatTicks;
		int eatSlot = -1;
		/** 近づけなかった敵は一定時間無視する（entity → 無視を解く tickCount） */
		final Map<LivingEntity, Integer> ignored = new WeakHashMap<>();
		int unreachTicks;
		LivingEntity lastEngaged;
		int repath;
		final Motion.FallGuard fall = new Motion.FallGuard();
		final Evade evade = new Evade();
		/** この tickCount までは避難を始めない（60秒待ったあとは慎重に進む） */
		int noFleeUntil;
		boolean backingOff;
		int creeperCd;
		int backoffTicks;
		Vec3 backoffFrom = Vec3.ZERO;
	}

	private static final Map<FakeMember, State> STATES = new WeakHashMap<>();

	static State state(FakeMember o) {
		return STATES.computeIfAbsent(o, k -> new State());
	}

	static boolean isFighting(FakeMember o) {
		State s = STATES.get(o);
		return s != null && (s.fighting || s.evade.active || s.backingOff);
	}

	/** SkillRunner.tick から毎tick。 */
	static void tick(FakeMember o) {
		if (!(o.level() instanceof ServerLevel l) || o.isPuddle() || !o.isAlive()) {
			State dead = STATES.get(o);
			if (dead != null && dead.evade.active) {
				dead.evade.abort(o); // Autopilot の止めを必ず解く
			}
			return;
		}
		State st = state(o);
		coolDown(o, st);
		st.fall.tick(o);
		if (o.tickCount % 3 == 0 && Motion.lavaBrake(o) && st.fighting) {
			st.backTicks = 0;
		}
		boolean purposeful = purposeful(o);
		boolean day = !Observer.isNight(l);
		// 周囲の敵を整理: こちらを狙っている敵（aggr）と、そうでない敵
		List<LivingEntity> aggr = aggressors(o, l, st);
		if (o.tickCount % 8 == 0 && !aggr.isEmpty()) {
			System.out.println("[combatdbg] y=" + (int) o.getY() + " aggr=" + aggr.size() + " evade=" + st.evade.active + " mode=" + st.evade.mode + " fighting=" + st.fighting + " noFlee=" + (st.noFleeUntil - o.tickCount) + " cd=" + st.cooldown + " target=" + st.target + " hp=" + o.getHealth());
		}
		if (st.evade.active) {
			if (!st.evade.tick(o, aggr, day)) {
				// どの終わり方でも20秒は再避難しない（避難→再開→また避難の往復を防ぐ）。待ち切ったら30秒
				st.noFleeUntil = o.tickCount + (st.evade.waitedOut() ? 600 : 400);
				endFight(o, st);
			}
			return;
		}
		if (o.tickCount >= st.noFleeUntil && shouldFlee(o, l, aggr, purposeful, day)) {
			st.fighting = false;
			lowerShield(o, st);
			st.evade.begin(o, aggr);
			if (st.evade.tick(o, aggr, day)) {
				return;
			}
			// 逃げ場が無く即終了: 避難せず、そのまま通常の戦いに進む（20秒は再避難しない）
			st.noFleeUntil = o.tickCount + 400;
		}
		// クリーパーは殴らない。近づかれたら離れる。ただし1回あたり最大6ブロック・40tick、そのあと4秒は後退しない（往復しない）。
		// 後退先は床があり落差2以内・溶岩でない向きだけ
		if (st.creeperCd > 0) {
			st.creeperCd--;
		}
		Creeper creeper = st.creeperCd > 0 ? null : nearestCreeper(o, l);
		if (creeper != null && !purposeful) {
			if (!st.backingOff) {
				st.backingOff = true;
				st.backoffFrom = o.position();
				st.backoffTicks = 0;
			}
			st.backoffTicks++;
			Vec3 away = o.position().subtract(creeper.position());
			Vec3 dir = Motion.safeAway(o, away);
			double moved = SkillUtil.horiz(o.position(), st.backoffFrom);
			if (dir == null || moved >= 6.0 || st.backoffTicks > 40) {
				st.backingOff = false;
				st.creeperCd = 80;
			} else {
				st.calm = 0;
				Motion.face(o, creeper.getEyePosition());
				o.setSprinting(true);
				Vec3 to = o.position().add(dir.scale(4));
				o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
				return;
			}
		} else {
			st.backingOff = false;
		}
		LivingEntity threat = scan(o, l, st, aggr, purposeful);
		if (threat == null) {
			if (st.fighting && ++st.calm > 40 && !st.retreating) {
				endFight(o, st);
			} else if (st.retreating) {
				recover(o, st, null);
			}
			return;
		}
		st.calm = 0;
		if (!st.fighting) {
			st.fighting = true;
			o.getNavigation().stop();
			o.setSprinting(false);
		}
		st.target = threat;
		fightTick(o, st, threat);
	}

	/** 戦うこと自体が今の目的（attack / hunt / エンドラ戦）か。 */
	static boolean purposeful(FakeMember o) {
		String s = o.skills().status();
		return s.contains("と戦っている") || s.contains("を狩っている") || s.startsWith("エンドラ戦");
	}

	private static boolean attackedMe(FakeMember o, LivingEntity e) {
		return o.getLastHurtByMob() == e && o.tickCount - o.getLastHurtByMobTimestamp() < 100;
	}

	/** いまこちらを狙っている／殴ってきた敵（14ブロック以内）。 */
	static List<LivingEntity> aggressors(FakeMember o, ServerLevel l, State st) {
		List<LivingEntity> out = new java.util.ArrayList<>();
		AABB box = o.getBoundingBox().inflate(14, 8, 14);
		for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, box, x -> isThreat(o, x))) {
			boolean targeting = e instanceof net.minecraft.world.entity.Mob m && m.getTarget() == o;
			if ((targeting || attackedMe(o, e)) && !isIgnored(o, st, e)) {
				out.add(e);
			}
		}
		out.sort((a, b) -> Double.compare(a.distanceToSqr(o), b.distanceToSqr(o)));
		return out;
	}

	private static Creeper nearestCreeper(FakeMember o, ServerLevel l) {
		Creeper best = null;
		double bd = 7 * 7;
		for (Creeper c : l.getEntitiesOfClass(Creeper.class, o.getBoundingBox().inflate(7, 4, 7), Creeper::isAlive)) {
			double d = c.distanceToSqr(o);
			if (d < bd && (d < 3 * 3 || o.hasLineOfSight(c))) {
				bd = d;
				best = c;
			}
		}
		return best;
	}

	/** 逃げる条件: 2体以上に狙われる／体力8以下／夜で武器が木以下／（目的の戦いでは体力8以下だけ）。 */
	private static boolean shouldFlee(FakeMember o, ServerLevel l, List<LivingEntity> aggr, boolean purposeful, boolean day) {
		if (aggr.isEmpty() || o.isPuddle()) {
			return false;
		}
		if (purposeful) {
			return o.getHealth() <= LOW_HP && Motion.scaffoldCount(o) >= 2;
		}
		if (aggr.size() >= 2 || o.getHealth() <= LOW_HP) {
			return true;
		}
		if (!day && weakWeapon(o)) {
			return true;
		}
		return false;
	}

	private static boolean weakWeapon(FakeMember o) {
		ItemStack w = Inv.bestWeapon(o);
		if (w.isEmpty()) {
			return true;
		}
		String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(w.getItem()).getPath();
		return p.startsWith("wooden_") || p.startsWith("golden_");
	}

	/** 同じtickに何度呼ばれても1回だけ減らす（Combat.tick が呼ばれない間も、スキル側から呼べる）。負の値 = 準備完了からの経過。 */
	static void coolDown(FakeMember o, State st) {
		if (st.stamp != o.tickCount) {
			st.stamp = o.tickCount;
			if (st.cooldown > -20) {
				st.cooldown--;
			}
		}
	}

	private static void endFight(FakeMember o, State st) {
		st.fighting = false;
		st.target = null;
		lowerShield(o, st);
		o.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		o.setSprinting(false);
	}

	// ---- 脅威の選択 ----

	private static LivingEntity scan(FakeMember o, ServerLevel l, State st, List<LivingEntity> aggr, boolean purposeful) {
		if (o.tickCount % 4 != 0 && st.target != null && st.target.isAlive() && o.distanceToSqr(st.target) < 14 * 14
				&& !isIgnored(o, st, st.target)) {
			return st.target;
		}
		AABB box = o.getBoundingBox().inflate(16, 8, 16);
		List<LivingEntity> list = l.getEntitiesOfClass(LivingEntity.class, box, e -> isThreat(o, e));
		// 戦うのは: こちらを狙っている／殴ってきた敵、進路を塞ぐ敵（すぐ目の前）、目的の戦い。それ以外の敵は避ける（近づかない）
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (LivingEntity e : list) {
			double d = e.distanceToSqr(o);
			if (isIgnored(o, st, e) || (e instanceof Creeper && st.creeperCd <= 0)) {
				continue;
			}
			if (!purposeful && !aggr.contains(e) && d > 2.2 * 2.2) {
				continue;
			}
			// 近接は「7ブロック以内で、見えている（または手が届く距離）」ときだけ。壁の向こう・洞窟の敵で戦闘が終わらなくなるのを防ぐ
			boolean melee = d < 7 * 7 && (d < 2.5 * 2.5 || o.hasLineOfSight(e));
			boolean ranged = isRanged(e) && d < 20 * 20 && o.hasLineOfSight(e);
			if (!melee && !ranged) {
				continue;
			}
			if (e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())) {
				d -= 100;
			}
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	private static boolean isIgnored(FakeMember o, State st, LivingEntity e) {
		Integer until = st.ignored.get(e);
		if (until == null) {
			return false;
		}
		if (o.tickCount >= until) {
			st.ignored.remove(e);
			return false;
		}
		return true;
	}

	static boolean isThreat(FakeMember o, LivingEntity e) {
		if (e == o || !e.isAlive() || e instanceof Player || e instanceof FakeMember || e instanceof EnderDragon) {
			return false;
		}
		LivingEntity hurtBy = o.getLastHurtByMob();
		boolean attackedMe = hurtBy == e && o.tickCount - o.getLastHurtByMobTimestamp() < 100;
		if (e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
				&& !(e instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute)) {
			// ピグリン・ゾンビ化ピグリン以外の AbstractPiglin は Enemy だが中立扱い。こちらを狙っている／殴られた時だけ反撃。取引中は殴らない
			if (o.skills().status().startsWith("ピグリンと取引")) {
				return false;
			}
			return attackedMe || (e instanceof net.minecraft.world.entity.Mob pm && pm.getTarget() == o);
		}
		if (e instanceof NeutralMob nm) {
			// エンダーマン・ゾンビピグリン等は怒っている（自分を狙っている）ときだけ
			return attackedMe || (nm.isAngry() && e instanceof net.minecraft.world.entity.Mob m && m.getTarget() == o);
		}
		if (e instanceof net.minecraft.world.entity.Mob m && !(e instanceof Enemy)) {
			return attackedMe && m.isAlive();
		}
		return e instanceof Enemy;
	}

	static boolean isRanged(LivingEntity e) {
		return e instanceof AbstractSkeleton || e instanceof net.minecraft.world.entity.monster.Blaze;
	}

	// ---- 1tick の戦闘 ----

	private static void fightTick(FakeMember o, State st, LivingEntity t) {
		boolean low = o.getHealth() <= LOW_HP;
		if (low && hasFood(o)) {
			st.retreating = true;
		}
		if (st.retreating) {
			if (o.getHealth() >= RECOVER_HP || !hasFood(o)) {
				st.retreating = false;
			} else {
				recover(o, st, t);
				return;
			}
		}
		equipBest(o, st);
		double dist = distTo(o, t);
		Vec3 aim = t.getEyePosition();
		Motion.face(o, aim);

		boolean creeperFuse = t instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited());
		boolean wantShield = hasShield(o)
				&& (creeperFuse || (isRanged(t) && dist > 3.5) || (t instanceof Creeper && dist < 6));
		if (creeperFuse && dist < 5.5) {
			// 導火線: 離れる（盾を構えたまま後退）
			setShield(o, st, true);
			moveRel(o, -1.0f, 0.0f, true);
			return;
		}
		if (st.backTicks > 0) {
			st.backTicks--;
			setShield(o, st, wantShield);
			moveRel(o, -1.0f, st.strafeDir * 0.5f, true);
			return;
		}
		if (dist > REACH + 0.2) {
			if (st.lastEngaged != t) {
				st.lastEngaged = t;
				st.unreachTicks = 0;
			}
			// 3秒近づけない敵は10秒無視（届かない敵で進行が止まらないように）
			if (++st.unreachTicks > 60 && !isRanged(t)) {
				st.ignored.put(t, o.tickCount + 200);
				st.unreachTicks = 0;
				st.target = null;
				st.calm = 41;
				return;
			}
			setShield(o, st, wantShield);
			approach(o, st, t, dist);
			return;
		}
		st.unreachTicks = 0;
		// 間合いの中: 横によけつつ、ジャンプ攻撃
		if (--st.strafeTicks <= 0) {
			st.strafeTicks = 12 + o.getRandom().nextInt(14);
			st.strafeDir = o.getRandom().nextBoolean() ? 1 : -1;
		}
		boolean ready = st.cooldown <= 0;
		if (ready) {
			setShield(o, st, false);
			boolean falling = !o.onGround() && o.getDeltaMovement().y < -0.05;
			if (falling || st.cooldown < -8 || o.isInWater()) {
				hit(o, st, t, falling);
				if (t instanceof Creeper) {
					st.backTicks = 8;
				}
			} else if (o.onGround()) {
				o.getJumpControl().jump();
			}
			moveRel(o, 0.3f, st.strafeDir * 0.8f, true);
		} else {
			setShield(o, st, wantShield);
			moveRel(o, dist < 2.0 ? -0.6f : 0.2f, st.strafeDir * 0.9f, true);
			if (st.cooldown < 6 && o.onGround() && dist < 3.2) {
				o.getJumpControl().jump();
			}
		}
	}

	private static void approach(FakeMember o, State st, LivingEntity t, double dist) {
		double mod = Motion.speedMod(o, true, 1.0);
		o.setSprinting(true);
		if (o.hasLineOfSight(t) && dist < 8) {
			// 見えていて近いなら直進。ジグザグに寄って矢をよける（スケルトン相手）
			Vec3 tp = t.position();
			if (isRanged(t) && dist > 4) {
				if (--st.strafeTicks <= 0) {
					st.strafeTicks = 8 + o.getRandom().nextInt(10);
					st.strafeDir = o.getRandom().nextBoolean() ? 1 : -1;
				}
				moveRel(o, 1.0f, st.strafeDir * 0.7f, true);
			} else {
				o.getMoveControl().setWantedPosition(tp.x, tp.y, tp.z, mod);
				if (o.onGround() && t.getY() > o.getY() + 0.6) {
					o.getJumpControl().jump();
				}
			}
		} else {
			if (--st.repath <= 0) {
				st.repath = 20; // 経路の再計算は1秒に1回まで
				o.getNavigation().moveTo(t, mod);
			}
		}
	}

	/** 向きを保ったまま前後・左右に動く。 */
	static void moveRel(FakeMember o, float fwd, float right, boolean sprint) {
		// 足場の端・溶岩・深い穴へは踏み出さない（戦闘中のよけ・後退で落ちない）
		double yaw = Math.toRadians(o.getYRot());
		double lx = -Math.sin(yaw);
		double lz = Math.cos(yaw);
		if (Math.abs(fwd) > 0.01) {
			double sg = Math.signum(fwd);
			if (Double.isNaN(Motion.groundAt(SkillUtil.level(o), o.getX() + lx * sg * 1.3, o.getZ() + lz * sg * 1.3, o.getY()))) {
				fwd = 0;
			}
		}
		if (Math.abs(right) > 0.01) {
			boolean bad = Double.isNaN(Motion.groundAt(SkillUtil.level(o), o.getX() + lz * 1.3, o.getZ() - lx * 1.3, o.getY()))
					|| Double.isNaN(Motion.groundAt(SkillUtil.level(o), o.getX() - lz * 1.3, o.getZ() + lx * 1.3, o.getY()));
			if (bad) {
				right = 0;
			}
		}
		o.setSprinting(sprint && fwd > 0);
		double mod = Motion.speedMod(o, o.isSprinting(), 1.0);
		o.getMoveControl().setWantedPosition(o.getX(), o.getY(), o.getZ(), mod);
		o.getMoveControl().strafe(fwd, right);
	}

	static double distTo(FakeMember o, net.minecraft.world.entity.Entity t) {
		Vec3 e = o.getEyePosition();
		AABB b = t.getBoundingBox();
		double x = Math.max(b.minX, Math.min(e.x, b.maxX));
		double y = Math.max(b.minY, Math.min(e.y, b.maxY));
		double z = Math.max(b.minZ, Math.min(e.z, b.maxZ));
		return e.distanceTo(new Vec3(x, y, z));
	}

	// ---- 攻撃（共有。AttackSkill / DragonSkill もこれを使う） ----

	/** 間合い内なら殴る。クールダウン中・遠いときは false。 */
	static boolean hit(FakeMember o, State st, net.minecraft.world.entity.Entity t, boolean crit) {
		if (st.cooldown > 0 || !(o.level() instanceof ServerLevel l)) {
			return false;
		}
		st.cooldown = o.getMainHandItem().is(ItemTags.AXES) ? AXE_COOLDOWN : COOLDOWN;
		SkillUtil.swing(o);
		AttributeInstance dmg = o.getAttribute(Attributes.ATTACK_DAMAGE);
		AttributeModifier critMod = new AttributeModifier(com.amaguri.aibuddy.AiBuddyMod.id("crit"),
				0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		if (crit && dmg != null) {
			dmg.addOrUpdateTransientModifier(critMod);
		}
		// UNVERIFIED: Mob.doHurtTarget は ATTACK_DAMAGE と手持ち武器の修飾子・エンチャントで傷つける。クリティカルは一時修飾子で1.5倍にする。
		boolean ok = o.doHurtTarget(l, t);
		if (crit && dmg != null) {
			dmg.removeModifier(critMod.id());
			l.sendParticles(ParticleTypes.CRIT, t.getX(), t.getY() + t.getBbHeight() * 0.6, t.getZ(), 12, 0.3, 0.3, 0.3, 0.2);
			l.playSound(null, t.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
		}
		return ok;
	}

	/** 手に持つ物を最強の武器にする（実物）。無ければ素手のまま。 */
	static void equipBest(FakeMember o, State st) {
		if (o.tickCount % 10 != 0) {
			return;
		}
		ItemStack w = Inv.bestWeapon(o);
		if (!w.isEmpty() && o.getMainHandItem() != w && !ItemStack.matches(o.getMainHandItem(), w)) {
			Inv.equip(o, w);
		}
	}

	// ---- 盾 ----

	static boolean hasShield(FakeMember o) {
		return Inv.count(o, Items.SHIELD) > 0;
	}

	static void setShield(FakeMember o, State st, boolean up) {
		if (up && !hasShield(o)) {
			up = false;
		}
		if (up == st.shieldUp) {
			return;
		}
		st.shieldUp = up;
		if (up) {
			ItemStack sh = ItemStack.EMPTY;
			for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
				ItemStack s = o.getInventory().getItem(i);
				if (s.is(Items.SHIELD)) {
					sh = s;
					break;
				}
			}
			// UNVERIFIED: 持ち物の実物を左手に持たせ、startUsingItem で構える（mob でも isBlocking が立つ前提）
			o.setItemSlot(EquipmentSlot.OFFHAND, sh);
			o.setDropChance(EquipmentSlot.OFFHAND, 0.0f);
			o.startUsingItem(InteractionHand.OFF_HAND);
		} else {
			o.stopUsingItem();
		}
	}

	private static void lowerShield(FakeMember o, State st) {
		if (st.shieldUp) {
			o.stopUsingItem();
			st.shieldUp = false;
		}
	}

	// ---- 体力が低いとき: 下がって食べる ----

	static boolean hasFood(FakeMember o) {
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			if (EatSkill.isFood(o.getInventory().getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/** threat から離れ、7ブロック離れたら食べる。食べている最中に近づかれたらまた下がる。 */
	static void recover(FakeMember o, State st, LivingEntity threat) {
		lowerShield(o, st);
		double d = threat == null ? 99 : Math.sqrt(o.distanceToSqr(threat));
		if (d < 7 && st.eatTicks == 0) {
			Vec3 away = o.position().subtract(threat.position());
			Vec3 h = new Vec3(away.x, 0, away.z);
			h = h.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : h.normalize();
			Vec3 to = o.position().add(h.scale(8));
			o.setSprinting(true);
			o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
			if (o.onGround() && o.tickCount % 12 == 0) {
				o.getJumpControl().jump();
			}
			return;
		}
		if (d < 2.5) {
			st.eatTicks = 0;
			o.stopUsingItem();
			return;
		}
		o.getNavigation().stop();
		o.setSprinting(false);
		if (st.eatTicks == 0) {
			st.eatSlot = bestFoodSlot(o);
			if (st.eatSlot < 0) {
				st.retreating = false;
				return;
			}
			o.setItemSlot(EquipmentSlot.MAINHAND, o.getInventory().getItem(st.eatSlot).copyWithCount(1));
			o.startUsingItem(InteractionHand.MAIN_HAND);
		}
		if (++st.eatTicks >= 34) {
			o.stopUsingItem();
			ItemStack s = o.getInventory().getItem(st.eatSlot);
			FoodProperties f = s.get(DataComponents.FOOD);
			if (f != null) {
				s.shrink(1);
				o.getInventory().setItem(st.eatSlot, s);
				o.heal(f.nutrition());
			}
			st.eatTicks = 0;
			st.eatSlot = -1;
			ItemStack w = Inv.bestWeapon(o);
			Inv.equip(o, w);
			if (o.getHealth() >= RECOVER_HP) {
				st.retreating = false;
			}
		}
	}

	private static int bestFoodSlot(FakeMember o) {
		int best = -1;
		int bn = -1;
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack s = o.getInventory().getItem(i);
			if (EatSkill.isFood(s)) {
				int n = s.get(DataComponents.FOOD).nutrition();
				if (n > bn) {
					bn = n;
					best = i;
				}
			}
		}
		return best;
	}

	/** DragonSkill 等が使う: 体力が低いときだけ、脅威から離れて食べる。処理中なら true。 */
	static boolean lowHealthRoutine(FakeMember o, Vec3 awayFrom) {
		State st = state(o);
		if (!st.retreating && (o.getHealth() > LOW_HP || !hasFood(o))) {
			return false;
		}
		st.retreating = true;
		if (o.getHealth() >= RECOVER_HP) {
			st.retreating = false;
			return false;
		}
		if (awayFrom != null && o.position().distanceToSqr(awayFrom) < 49 && st.eatTicks == 0) {
			Vec3 away = o.position().subtract(awayFrom);
			Vec3 h = new Vec3(away.x, 0, away.z);
			h = h.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : h.normalize();
			Vec3 to = o.position().add(h.scale(8));
			o.setSprinting(true);
			o.getMoveControl().setWantedPosition(to.x, o.getY(), to.z, Motion.speedMod(o, true, 1.0));
			return true;
		}
		recover(o, st, null);
		return true;
	}
}
