package com.amaguri.aibuddy.skill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaguri.aibuddy.entity.FakeMember;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 脳が選んだ行動（ツール）を実行する手足。 */
public final class SkillRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger("aibuddy-skill");

	private final FakeMember owner;
	private final AutoReporter reporter;
	private Skill current;
	private String lastResult = "";
	private ItemStack savedHand;
	private boolean handHeld;
	int pickedUp;

	public SkillRunner(FakeMember owner) {
		this.owner = owner;
		this.reporter = new AutoReporter(owner);
	}

	public static JsonArray toolSpecs() {
		return Tools.specs();
	}

	public synchronized String start(String tool, JsonObject input) {
		if (!(owner.level() instanceof ServerLevel)) {
			return "できない: まだ世界にいない";
		}
		if (owner.isPuddle()) {
			return "できない: 溶けて水たまりになっている";
		}
		if (input == null) {
			input = new JsonObject();
		}
		if ("stop".equals(tool)) {
			stop();
			return "止まった";
		}
		Skill next;
		try {
			next = create(tool, input);
		} catch (RuntimeException e) {
			LOGGER.warn("skill create failed: {}", tool, e);
			return "できない: " + e.getMessage();
		}
		if (next == null) {
			return "できない: 知らないツール " + tool;
		}
		stopCurrentQuietly();
		String err;
		try {
			err = next.begin();
		} catch (RuntimeException e) {
			LOGGER.warn("skill begin failed: {}", tool, e);
			next.onStop();
			return "できない: " + e.getMessage();
		}
		if (err != null) {
			next.onStop();
			releaseHand();
			return err.startsWith("できない") ? err : "できない: " + err;
		}
		current = next;
		return "開始した: " + next.status();
	}

	private Skill create(String tool, JsonObject in) {
		return switch (tool) {
			case "follow_player" -> new FollowSkill(owner, this, str(in, "name"));
			case "goto" -> new GotoSkill(owner, this, num(in, "x"), num(in, "y"), num(in, "z"));
			case "explore" -> new ExploreSkill(owner, this, str(in, "direction"));
			case "mine" -> new MineSkill(owner, this, str(in, "block"), in.has("count") ? (int) num(in, "count") : 5);
			case "collect_items" -> new CollectSkill(owner, this);
			case "attack" -> new AttackSkill(owner, this, str(in, "target"));
			case "find_structure" -> new StructureSkill(owner, this, str(in, "type"));
			case "build_nether_portal" -> new PortalSkill(owner, this);
			case "eat" -> new EatSkill(owner, this);
			case "emote" -> new EmoteSkill(owner, this, str(in, "kind"));
			default -> null;
		};
	}

	static String str(JsonObject in, String key) {
		JsonElement e = in.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	static double num(JsonObject in, String key) {
		JsonElement e = in.get(key);
		if (e == null || e.isJsonNull()) {
			throw new IllegalArgumentException(key + " が必要");
		}
		return e.getAsDouble();
	}

	public synchronized void tick() {
		if (!(owner.level() instanceof ServerLevel sl)) {
			return;
		}
		try {
			reporter.tick();
			pickupNearby(sl);
			if (owner.isPuddle()) {
				if (current != null) {
					stop();
				}
				return;
			}
			if (current != null) {
				current.tick();
				if (current.finished) {
					Skill s = current;
					current = null;
					lastResult = s.result == null ? "" : s.result;
					s.onStop();
					releaseHand();
					if (s.event != null) {
						owner.postEvent(s.event);
					}
				}
			}
		} catch (RuntimeException e) {
			LOGGER.warn("skill tick failed", e);
			lastResult = "失敗: 内部エラー";
			stopCurrentQuietly();
		}
	}

	public synchronized void stop() {
		stopCurrentQuietly();
		lastResult = "止めた";
	}

	private void stopCurrentQuietly() {
		if (current != null) {
			try {
				current.onStop();
			} catch (RuntimeException e) {
				LOGGER.warn("skill stop failed", e);
			}
			current = null;
		}
		owner.getNavigation().stop();
		owner.setSprinting(false);
		releaseHand();
	}

	public synchronized boolean isBusy() {
		return current != null && !current.finished;
	}

	public synchronized String status() {
		if (current != null && !current.finished) {
			return current.status();
		}
		return lastResult.isEmpty() ? "何もしていない" : "何もしていない（直前: " + lastResult + "）";
	}

	public synchronized String observe() {
		return Observer.observe(owner);
	}

	// ---- 手持ち（見た目だけ）----

	/** 腕に持たせる（見た目用。持ち物には入れない）。終了時に元に戻す。 */
	void hold(Item item) {
		ItemStack now = owner.getMainHandItem();
		if (!now.isEmpty() && now.is(item)) {
			return;
		}
		if (!handHeld) {
			savedHand = now.copy();
			handHeld = true;
		}
		owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
		owner.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
	}

	void holdStack(ItemStack stack) {
		if (!handHeld) {
			savedHand = owner.getMainHandItem().copy();
			handHeld = true;
		}
		owner.setItemSlot(EquipmentSlot.MAINHAND, stack);
		owner.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
	}

	private void releaseHand() {
		if (handHeld) {
			owner.setItemSlot(EquipmentSlot.MAINHAND, savedHand == null ? ItemStack.EMPTY : savedHand);
			savedHand = null;
			handHeld = false;
		}
	}

	// ---- 落ちた物を拾う（常時）----

	private void pickupNearby(ServerLevel sl) {
		if (owner.tickCount % 2 != 0) {
			return;
		}
		List<ItemEntity> list = SkillUtil.items(owner, 1.6);
		for (ItemEntity ie : list) {
			if (ie.hasPickUpDelay() || ie.distanceToSqr(owner) > 2.6 * 2.6) {
				continue;
			}
			ItemStack st = ie.getItem();
			int before = st.getCount();
			ItemStack left = owner.getInventory().addItem(st.copy());
			int taken = before - left.getCount();
			if (taken <= 0) {
				continue;
			}
			pickedUp += taken;
			if (left.isEmpty()) {
				ie.discard();
			} else {
				ie.setItem(left);
			}
			sl.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
					0.2f, 1.0f + owner.getRandom().nextFloat() * 0.6f);
		}
	}
}
