package com.amaguri.aibuddy.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.amaguri.aibuddy.AiBuddyMod;
import com.amaguri.aibuddy.brain.BrainManager;
import com.amaguri.aibuddy.item.ModItems;
import com.amaguri.aibuddy.skill.SkillRunner;

/** プレイヤーのふりをするAIメンバー。溶ける。 */
public class FakeMember extends PathfinderMob {
	private static final EntityDataAccessor<Float> DATA_MELT =
			SynchedEntityData.defineId(FakeMember.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<String> DATA_SKIN =
			SynchedEntityData.defineId(FakeMember.class, EntityDataSerializers.STRING);
	/** スキンの ResolvableProfile（サーバーが名前から解決して textures 付きにし、クライアントへ同期する。Mannequin と同じ方式）。 */
	private static final EntityDataAccessor<ResolvableProfile> DATA_PROFILE =
			SynchedEntityData.defineId(FakeMember.class, EntityDataSerializers.RESOLVABLE_PROFILE);
	private static final EntityDataAccessor<String> DATA_DISPLAY_NAME =
			SynchedEntityData.defineId(FakeMember.class, EntityDataSerializers.STRING);

	/** 約10分(12000tick)で 0 -> 1。 */
	private static final float MELT_PER_TICK = 1f / 12000f;
	/** 溶けを進める間隔(tick)。同期パケットを減らすため。 */
	private static final int MELT_STEP_INTERVAL = 10;
	private static final float MELT_SLOW_START = 0.5f;
	private static final double MAX_MELT_SLOWDOWN = 0.4;
	private static final Identifier MELT_SLOW_ID = AiBuddyMod.id("melt_slow");
	private static final int MAX_EVENTS = 64;
	private static final int INVENTORY_SIZE = 36;
	private static final float TAKOYAKI_HEAL = 0.5f;

	/** /aibuddy freeze で全員の溶けを止める。 */
	private static volatile boolean meltFrozen;

	private String personaId = "";
	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
	private SkillRunner skills;
	private com.amaguri.aibuddy.run.Autopilot autopilot;
	private boolean attached;
	/** リスポーン地点のベッド（頭側）。無ければ null。 */
	private net.minecraft.core.BlockPos bedPos;
	private net.minecraft.resources.ResourceKey<Level> bedDim;
	/** die() で持ち物の扱いを決めた（読込直後の死にかけ個体では false）。 */
	private boolean deathHandled;
	private boolean keepInventoryOnDeath;
	private boolean respawned;
	private int respawnFailures;
	private int respawnRetryAt = RESPAWN_DELAY_TICKS;
	/** 最後に寝た夜の日数（一晩1回の印）。-1 なら未。 */
	private long sleptDay = -1;
	/** 読込直後の初tickで、寝たまま保存されていたら起こす。 */
	private boolean wakeOnLoadCheck;
	/** リスポーン再試行の最大回数（超えたらワールドスポーンへ）。 */
	private static final int RESPAWN_MAX_RETRIES = 5;
	private static final int RESPAWN_RETRY_TICKS = 20;
	/** 死んでからリスポーンするまで（tick）。 */
	public static final int RESPAWN_DELAY_TICKS = 60;
	private net.minecraft.world.phys.Vec3 lastTickPos;
	/** 1tickでこれ以上動いたらテレポートとみなす（ブロック）。 */
	private static final double TELEPORT_JUMP = 6.0;
	private float lastAppliedSlowMelt = -1f;

	public FakeMember(EntityType<? extends FakeMember> type, Level level) {
		super(type, level);
		setCanPickUpLoot(true);
		setPersistenceRequired();
		// 足を置く先の共通の関門: どの移動（戦闘・避難・経路・スキル）も溶岩・火・大きな落下へ踏み出さず、跳び込まない
		this.moveControl = new com.amaguri.aibuddy.skill.SafeFooting.Move(this);
		this.jumpControl = new com.amaguri.aibuddy.skill.SafeFooting.Jump(this);
		// 死んだとき装備も必ず落とす（keepInventory のときは dropCustomDeathLoot 側で落とさず引き継ぐ）
		for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
			if (slot != net.minecraft.world.entity.EquipmentSlot.BODY && slot != net.minecraft.world.entity.EquipmentSlot.SADDLE) {
				setGuaranteedDrop(slot); // UNVERIFIED: 装備の落としかたは Mob.dropCustomDeathLoot 任せ（gametest で確認）
			}
		}
	}

	// ---- bed / respawn point ----

	public net.minecraft.core.BlockPos getBedPos() {
		return bedPos;
	}

	public net.minecraft.resources.ResourceKey<Level> getBedDim() {
		return bedDim;
	}

	public long getSleptDay() {
		return sleptDay;
	}

	public void setSleptDay(long day) {
		this.sleptDay = day;
	}

	/** リスポーン地点のベッドを覚える（null で忘れる）。 */
	public void setBed(net.minecraft.core.BlockPos pos, net.minecraft.resources.ResourceKey<Level> dim) {
		this.bedPos = pos == null ? null : pos.immutable();
		this.bedDim = pos == null ? null : dim;
	}

	public static AttributeSupplier.Builder createAttributes() {
		// MOVEMENT_SPEED 0.25: mob の移動は加速度 = 0.98 * (speedModifier * 属性)^2 なので、
		// 歩き4.3 / ダッシュ5.6 / ダッシュジャンプ7 ブロック/秒になる speedModifier を skill/Motion が逆算する。
		// 属性値は skill/Motion.BASE_SPEED と同じ値にすること（式の根拠は Motion のコメント。UNVERIFIED: 実測は gametest）。
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.ATTACK_DAMAGE, 1.0)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.STEP_HEIGHT, 1.0)
				// 上級者は足場の端で押されても踏ん張る（狭い足場で殴られて溶岩・奈落に落ちる事故を減らす）
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.5);
	}

	public static boolean isMeltFrozen() {
		return meltFrozen;
	}

	public static void setMeltFrozen(boolean frozen) {
		meltFrozen = frozen;
	}

	// ---- setup ----

	@Override
	protected PathNavigation createNavigation(Level level) {
		// NOTE: called from the Mob constructor; must not touch FakeMember fields.
		GroundPathNavigation nav = new GroundPathNavigation(this, level);
		nav.setCanOpenDoors(true);
		nav.setCanFloat(true);
		return nav;
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_MELT, 0f);
		builder.define(DATA_SKIN, "");
		builder.define(DATA_PROFILE, ResolvableProfile.Static.EMPTY);
		builder.define(DATA_DISPLAY_NAME, "");
	}

	// ---- public API (CONTRACT) ----

	public float getMelt() {
		return this.entityData.get(DATA_MELT);
	}

	public void setMelt(float v) {
		float nv = Math.max(0f, Math.min(1f, v));
		float old = getMelt();
		if (nv == old) {
			return;
		}
		this.entityData.set(DATA_MELT, nv);
		if (level().isClientSide()) {
			return;
		}
		updateMeltSlow();
		if (old < 0.5f && nv >= 0.5f) {
			postEvent("体が半分溶けてきた");
		}
		if (old < 1f && nv >= 1f) {
			postEvent("完全に溶けて水たまりになった");
			haltForPuddle();
		}
	}

	public String getSkinName() {
		return this.entityData.get(DATA_SKIN);
	}

	public void setSkinName(String skin) {
		String n = skin == null ? "" : skin;
		if (!n.equals(getSkinName())) {
			skinResolveState = 0;
		}
		this.entityData.set(DATA_SKIN, n);
	}

	/** 同期済みのスキンプロフィール。未設定なら EMPTY（クライアントは既定スキンにする）。 */
	public ResolvableProfile getProfile() {
		return this.entityData.get(DATA_PROFILE);
	}

	/** 0=未着手 1=解決中 2=完了/失敗（名前を変えたら 0 に戻る） */
	private int skinResolveState;

	/** サーバー側: スキン名からプロフィール（textures 付き）を非同期で解決して同期する。失敗時はログ1回で、名前だけのプロフィールのまま（クライアント側解決に任せる）。 */
	private void ensureProfile() {
		String name = getSkinName();
		if (skinResolveState != 0 || name.isBlank() || !(level() instanceof ServerLevel sl)) {
			return;
		}
		skinResolveState = 1;
		ResolvableProfile unresolved = ResolvableProfile.createUnresolved(name);
		this.entityData.set(DATA_PROFILE, unresolved);
		net.minecraft.server.MinecraftServer server = sl.getServer();
		unresolved.resolveProfile(server.services().profileResolver()).whenComplete((profile, err) -> server.execute(() -> {
			if (isRemoved() || !name.equals(getSkinName())) {
				return;
			}
			skinResolveState = 2;
			if (err != null || profile == null) {
				AiBuddyMod.LOGGER.warn("スキンのプロフィールを解決できなかった（既定スキンになる可能性）: {} {}", name, err == null ? "" : err.toString());
				return;
			}
			this.entityData.set(DATA_PROFILE, ResolvableProfile.createResolved(profile));
		}));
	}

	public String getDisplayNameText() {
		return this.entityData.get(DATA_DISPLAY_NAME);
	}

	public void setDisplayNameText(String name) {
		String n = name == null ? "" : name;
		this.entityData.set(DATA_DISPLAY_NAME, n);
		// customName を同じ名前にして @e[name=...] で選べるようにする（名前タグはクライアント側 Renderer が自前で描くので二重にならない）
		setCustomName(n.isEmpty() ? null : Component.literal(n));
		setCustomNameVisible(false);
	}

	public boolean isPuddle() {
		return getMelt() >= 1f;
	}

	public String getPersonaId() {
		return personaId;
	}

	public void setPersonaId(String id) {
		this.personaId = id == null ? "" : id;
	}

	public SimpleContainer getInventory() {
		return inventory;
	}

	public SkillRunner skills() {
		if (skills == null) {
			skills = new SkillRunner(this);
		}
		return skills;
	}

	/** 自動進行（W3）。召喚直後は on。 */
	public com.amaguri.aibuddy.run.Autopilot autopilot() {
		if (autopilot == null) {
			autopilot = new com.amaguri.aibuddy.run.Autopilot(this);
		}
		return autopilot;
	}

	/** サーバー全員のチャットに、プレイヤー発言と同じ見た目で流す。溶け>0.5 なら崩す。 */
	public void say(String text) {
		if (text == null || text.isBlank() || !(level() instanceof ServerLevel serverLevel)) {
			return;
		}
		String out = getMelt() > 0.5f ? Slur.apply(text, getMelt(), getRandom()) : text;
		String line = "<" + getDisplayNameText() + "> " + out;
		serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal(line), false);
		AiBuddyMod.LOGGER.info("[chat] {}", line);
	}

	/** 次元移動で作り直されたとき、未処理の出来事を引き継ぎ、移動を報告する。 */
	@Override
	public void restoreFrom(net.minecraft.world.entity.Entity old) {
		super.restoreFrom(old);
		if (old instanceof FakeMember prev) {
			events.addAll(prev.drainEvents());
			skills().inheritFrom(prev.skills());
			if (this.level() instanceof net.minecraft.server.level.ServerLevel sl && !prev.level().dimension().equals(sl.dimension())) {
				postEvent(com.amaguri.aibuddy.skill.Observer.dimText(sl) + "に移動した");
			}
		}
	}

	public void postEvent(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		events.add(text);
		while (events.size() > MAX_EVENTS) {
			events.poll();
		}
	}

	public List<String> drainEvents() {
		List<String> out = new ArrayList<>();
		String e;
		while ((e = events.poll()) != null) {
			out.add(e);
		}
		return out;
	}

	/** 脳(C)に一度だけ attach する。spawn コマンドと、ワールド読込後の初tickから呼ばれる。 */
	public void ensureAttached() {
		if (!attached && !personaId.isEmpty() && !level().isClientSide()) {
			attached = true;
			BrainManager.attach(this);
		}
	}

	// ---- behaviour ----

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			return;
		}
		ensureProfile();
		// 水に触れていないのに泳ぎ姿勢が残っていたら戻す（陸で寝そべったまま歩かない）
		if ((isSwimming() || getPose() == net.minecraft.world.entity.Pose.SWIMMING) && !isInWater()) {
			setSwimming(false);
			setPose(net.minecraft.world.entity.Pose.STANDING);
		}
		// /tp などで大きく飛んだら、古い目標への経路を捨てて Nav に引き直させる
		net.minecraft.world.phys.Vec3 now = position();
		if (lastTickPos != null && now.distanceToSqr(lastTickPos) > TELEPORT_JUMP * TELEPORT_JUMP) {
			getNavigation().stop();
			setSprinting(false);
		}
		lastTickPos = now;
		if (isDeadOrDying()) {
			return; // 死んでいる間は進行もスキルも止める（リスポーンは tickDeath）
		}
		if (wakeOnLoadCheck) {
			wakeOnLoadCheck = false;
			if (isSleeping()) {
				stopSleeping(); // 寝たまま保存されていても起きる
			}
		}
		// 寝ているのに sleep スキルが動いていなければ起こす（寝たまま固まらない）
		if (isSleeping() && !skills().currentName().startsWith("Sleep")) {
			stopSleeping();
		}
		ensureAttached();
		if (!meltFrozen && !isPuddle() && this.tickCount % MELT_STEP_INTERVAL == 0) {
			setMelt(getMelt() + MELT_PER_TICK * MELT_STEP_INTERVAL);
		}
		if (isPuddle()) {
			if (skills().isBusy()) {
				haltForPuddle();
			}
		} else {
			try {
				autopilot().tick();
			} catch (RuntimeException e) {
				com.amaguri.aibuddy.AiBuddyMod.LOGGER.warn("autopilot tick failed", e);
			}
			skills().tick();
		}
	}

	private void haltForPuddle() {
		skills().stop();
		getNavigation().stop();
		setDeltaMovement(0, getDeltaMovement().y, 0);
	}

	/** 溶け0.5以上で移動速度を最大40%まで下げる（0.5で0%、1.0で40%）。 */
	private void updateMeltSlow() {
		float melt = getMelt();
		if (melt == lastAppliedSlowMelt) {
			return;
		}
		lastAppliedSlowMelt = melt;
		AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		if (melt < MELT_SLOW_START) {
			speed.removeModifier(MELT_SLOW_ID);
			return;
		}
		double ratio = (melt - MELT_SLOW_START) / (1f - MELT_SLOW_START);
		speed.addOrUpdateTransientModifier(new AttributeModifier(
				MELT_SLOW_ID, -MAX_MELT_SLOWDOWN * ratio, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	@Override
	protected boolean isImmobile() {
		// 水たまり中・寝ている間は動けない（ジャンプ・移動入力が無効になる）。
		return isPuddle() || isSleeping() || super.isImmobile();
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (held.getItem() == ModItems.TAKOYAKI) {
			if (level() instanceof ServerLevel serverLevel) {
				held.consume(1, player);
				setMelt(getMelt() - TAKOYAKI_HEAL);
				playSound(SoundEvents.GENERIC_EAT.value(), 1.0f, 1.0f);
				serverLevel.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ModItems.TAKOYAKI),
						getX(), getEyeY(), getZ(), 12, 0.2, 0.2, 0.2, 0.05);
				postEvent(player.getName().getString() + "にたこ焼きをもらった");
			}
			return InteractionResult.SUCCESS;
		}
		return super.mobInteract(player, hand);
	}

	// ---- naming ----

	@Override
	public Component getName() {
		String n = getDisplayNameText();
		return n.isEmpty() ? super.getName() : Component.literal(n);
	}

	// ---- spawn / despawn ----

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public boolean checkSpawnRules(LevelAccessor level, EntitySpawnReason reason) {
		return reason != EntitySpawnReason.NATURAL;
	}

	@Override
	public void onRemoval(Entity.RemovalReason reason) {
		if (attached && !level().isClientSide()) {
			attached = false;
			BrainManager.detach(this);
		}
		if (skills != null) {
			skills.stop();
		}
		super.onRemoval(reason);
	}

	// ---- item pickup ----

	@Override
	public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
		return !isPuddle() && inventory.canAddItem(stack);
	}

	@Override
	protected void pickUpItem(ServerLevel level, ItemEntity entity) {
		ItemStack stack = entity.getItem();
		int before = stack.getCount();
		ItemStack rest = inventory.addItem(stack.copy());
		int picked = before - rest.getCount();
		if (picked > 0) {
			onItemPickup(entity);
			take(entity, picked);
		}
		if (rest.isEmpty()) {
			entity.discard();
		} else {
			entity.setItem(rest);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (isSleeping()) {
			stopSleeping(); // 殴られたら起きる
		}
		return super.hurtServer(level, source, amount);
	}

	// ---- death / respawn ----

	@Override
	public void die(DamageSource source) {
		boolean first = !this.dead && !isRemoved();
		Component msg = null;
		if (first && level() instanceof ServerLevel sl) {
			msg = getCombatTracker().getDeathMessage();
			keepInventoryOnDeath = sl.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
			deathHandled = true;
			if (isSleeping()) {
				stopSleeping();
			}
			skills().stop();
			getNavigation().stop();
			if (sl.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SHOW_DEATH_MESSAGES)) {
				sl.getServer().getPlayerList().broadcastSystemMessage(msg, false);
			}
			AiBuddyMod.LOGGER.info("[death] {}", msg.getString());
			postEvent("死んだ: " + msg.getString());
		}
		super.die(source);
	}

	/** 死亡アニメ（約1秒）のあとも RESPAWN_DELAY_TICKS まで体を残し、そこで新しい体に入れ替える。保存されても再開できる。 */
	@Override
	protected void tickDeath() {
		if (level().isClientSide() || isRemoved() || !(level() instanceof ServerLevel sl)) {
			super.tickDeath();
			return;
		}
		this.deathTime++;
		if (this.deathTime >= respawnRetryAt && !respawned) {
			boolean ok = false;
			try {
				ok = respawnAsNewBody(sl, respawnFailures >= RESPAWN_MAX_RETRIES);
			} catch (RuntimeException e) {
				AiBuddyMod.LOGGER.warn("respawn failed for {}", getDisplayNameText(), e);
			}
			if (ok) {
				respawned = true;
				sl.broadcastEntityEvent(this, (byte) 60);
				remove(Entity.RemovalReason.KILLED); // 新しい体を追加できたときだけ古い体を消す
			} else if (respawnFailures >= RESPAWN_MAX_RETRIES) {
				// ワールドスポーンでも駄目: 死体は残さず消す。人格は BrainManager に残るので /aibuddy spawn で呼び直せる
				respawned = true;
				AiBuddyMod.LOGGER.error("respawn gave up for {} (persona {}); use /aibuddy spawn", getDisplayNameText(), personaId);
				sl.broadcastEntityEvent(this, (byte) 60);
				remove(Entity.RemovalReason.KILLED);
			} else {
				respawnFailures++;
				respawnRetryAt = this.deathTime + RESPAWN_RETRY_TICKS;
			}
		}
	}

	private boolean respawnAsNewBody(ServerLevel from, boolean forceWorldSpawn) {
		net.minecraft.server.MinecraftServer server = from.getServer();
		ServerLevel target = null;
		net.minecraft.world.phys.Vec3 pos = null;
		float yaw = getYRot();
		boolean lostBed = false;
		if (!forceWorldSpawn && bedPos != null && bedDim != null) {
			ServerLevel bl = server.getLevel(bedDim);
			if (bl != null && bl.getBlockState(bedPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
				net.minecraft.core.Direction dir = net.minecraft.world.level.block.BedBlock.getBedOrientation(bl, bedPos);
				java.util.Optional<net.minecraft.world.phys.Vec3> stand =
						net.minecraft.world.level.block.BedBlock.findStandUpPosition(ModEntities.FAKE_MEMBER, bl, bedPos, dir, getYRot());
				if (stand.isPresent()) {
					target = bl;
					pos = stand.get();
				}
			}
			lostBed = pos == null; // 壊れた/塞がれた
		}
		if (pos == null) {
			// ワールドのスポーン（ネザー・エンドで死んでもオーバーワールドへ）
			target = server.overworld();
			net.minecraft.core.BlockPos sp = target.getHeightmapPos(
					net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getRespawnData().pos());
			pos = new net.minecraft.world.phys.Vec3(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
		}
		FakeMember n = ModEntities.FAKE_MEMBER.create(target, EntitySpawnReason.TRIGGERED);
		if (n == null) {
			return false;
		}
		n.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
		n.setPersonaId(personaId);
		n.setSkinName(getSkinName());
		n.setDisplayNameText(getDisplayNameText());
		if (!lostBed) {
			n.setBed(bedPos, bedDim);
		}
		boolean keep = keepInventoryOnDeath || !deathHandled; // 読込直後に死にかけだった個体は持ち物を失わない
		if (keep) {
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				n.inventory.setItem(i, inventory.getItem(i).copy());
			}
			for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
				if (slot != net.minecraft.world.entity.EquipmentSlot.BODY && slot != net.minecraft.world.entity.EquipmentSlot.SADDLE) {
					n.setItemSlot(slot, getItemBySlot(slot).copy());
				}
			}
		}
		// Autopilot の段階・合図済みか・場所の記憶(marks)と、報告済み記録・未処理の出来事を引き継ぐ
		n.autopilot().load(autopilot().save());
		n.skills().inheritFrom(skills());
		n.sleptDay = sleptDay;
		if (!target.addFreshEntity(n)) {
			n.discard();
			return false;
		}
		n.events.addAll(drainEvents());
		if (lostBed) {
			n.postEvent("ベッドが無くなっていた");
		}
		n.postEvent("リスポーンした");
		return true;
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		if (keepInventoryOnDeath) {
			return; // keepInventory: 落とさず新しい体へ引き継ぐ
		}
		super.dropCustomDeathLoot(level, source, killedByPlayer);
		for (ItemStack stack : inventory.removeAllItems()) {
			if (!stack.isEmpty()) {
				spawnAtLocation(level, stack);
			}
		}
	}

	// ---- save / load ----

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("PersonaId", personaId);
		output.putString("Skin", getSkinName());
		output.putString("DisplayName", getDisplayNameText());
		output.putFloat("Melt", getMelt());
		if (bedPos != null && bedDim != null) {
			output.putLong("BedPos", bedPos.asLong());
			output.putString("BedDim", bedDim.identifier().toString());
		}
		output.putLong("SleptDay", sleptDay);
		// UNVERIFIED: ItemStack.CODEC (non-optional) assumed correct for storeAsItemList (empty slots are skipped).
		inventory.storeAsItemList(output.list("Inventory", ItemStack.CODEC));
		output.store("Autopilot", net.minecraft.nbt.CompoundTag.CODEC, autopilot().save());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.personaId = input.getStringOr("PersonaId", "");
		setSkinName(input.getStringOr("Skin", ""));
		setDisplayNameText(input.getStringOr("DisplayName", ""));
		// set directly: loading must not fire threshold events
		this.entityData.set(DATA_MELT, Math.max(0f, Math.min(1f, input.getFloatOr("Melt", 0f))));
		this.inventory.fromItemList(input.listOrEmpty("Inventory", ItemStack.CODEC));
		input.read("Autopilot", net.minecraft.nbt.CompoundTag.CODEC).ifPresent(t -> autopilot().load(t));
		input.getLong("BedPos").ifPresent(l -> {
			String d = input.getStringOr("BedDim", "minecraft:overworld");
			this.bedPos = net.minecraft.core.BlockPos.of(l);
			this.bedDim = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(d));
		});
		this.sleptDay = input.getLongOr("SleptDay", -1L);
		this.wakeOnLoadCheck = true;
		this.lastAppliedSlowMelt = -1f;
		if (!level().isClientSide()) {
			updateMeltSlow();
		}
	}
}
