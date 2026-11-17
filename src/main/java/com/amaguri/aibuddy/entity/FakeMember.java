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
	private boolean attached;
	private float lastAppliedSlowMelt = -1f;

	public FakeMember(EntityType<? extends FakeMember> type, Level level) {
		super(type, level);
		setCanPickUpLoot(true);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		// MOVEMENT_SPEED 0.13 = player base 0.1 x sprint 1.3.
		// UNVERIFIED: mob movement-speed attribute may translate to blocks/s differently than the player's; tune in-game.
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.13)
				.add(Attributes.ATTACK_DAMAGE, 1.0)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.STEP_HEIGHT, 1.0);
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
		this.entityData.set(DATA_SKIN, skin == null ? "" : skin);
	}

	public String getDisplayNameText() {
		return this.entityData.get(DATA_DISPLAY_NAME);
	}

	public void setDisplayNameText(String name) {
		this.entityData.set(DATA_DISPLAY_NAME, name == null ? "" : name);
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
		ensureAttached();
		if (!meltFrozen && !isPuddle() && this.tickCount % MELT_STEP_INTERVAL == 0) {
			setMelt(getMelt() + MELT_PER_TICK * MELT_STEP_INTERVAL);
		}
		if (isPuddle()) {
			if (skills().isBusy()) {
				haltForPuddle();
			}
		} else {
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
		// 水たまり中は動けない（ジャンプ・移動入力が無効になる）。
		return isPuddle() || super.isImmobile();
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
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
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
		// UNVERIFIED: ItemStack.CODEC (non-optional) assumed correct for storeAsItemList (empty slots are skipped).
		inventory.storeAsItemList(output.list("Inventory", ItemStack.CODEC));
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
		this.lastAppliedSlowMelt = -1f;
		if (!level().isClientSide()) {
			updateMeltSlow();
		}
	}
}
