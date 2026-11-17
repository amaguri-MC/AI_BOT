package com.amaguri.aibuddy.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaguri.aibuddy.entity.FakeMember;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 行動（スキル）を実行する手足。行動を決めるのは主に Autopilot（run パッケージ）で、脳はたまに割り込むだけ。
 * スキルは SurvivalSkills → MotionSkills → RunSkills の順に名前から作る（知らない名前は null を返す決まり）。
 */
public final class SkillRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger("aibuddy-skill");

	private final FakeMember owner;
	private final AutoReporter reporter;
	private Skill current;
	private String lastResult = "";
	private ItemStack savedHand;
	private boolean handHeld;
	int pickedUp;
	/** 覚えておく場所（ゲート・要塞など）。Autopilot が保存/読込する。 */
	private final Map<String, BlockPos> marks = new HashMap<>();
	/** 脳から follow_player されたときに Autopilot を止める長さ（60秒） */
	private static final int FOLLOW_PAUSE_TICKS = 20 * 60;

	public SkillRunner(FakeMember owner) {
		this.owner = owner;
		this.reporter = new AutoReporter(owner);
	}

	/** 脳用のツール（say 以外）: follow_player / stop_follow / resume_run だけ。進行は Autopilot が決める。 */
	public static JsonArray toolSpecs() {
		return Tools.specs();
	}

	/**
	 * 脳（ツール呼び出し）からの入口。follow_player / stop_follow / resume_run は Autopilot の止め・再開も行う。
	 * follow_player の引数は {name} と {player} のどちらでも受ける。
	 */
	public synchronized String start(String tool, JsonObject input) {
		if (input == null) {
			input = new JsonObject();
		}
		if ("follow_player".equals(tool)) {
			normalizeFollow(input);
			String r = startAuto(tool, input);
			if (r.startsWith("開始")) {
				owner.autopilot().pause(FOLLOW_PAUSE_TICKS, "ついていく");
			}
			return r;
		}
		if ("stop_follow".equals(tool)) {
			if (current != null && current.getClass().getSimpleName().startsWith("Follow")) {
				stop();
			}
			owner.autopilot().resume();
			return "ついていくのをやめた。自分の進行に戻る";
		}
		if ("resume_run".equals(tool)) {
			owner.autopilot().setEnabled(true);
			owner.autopilot().resume();
			return "進行を再開した: " + owner.autopilot().goalText();
		}
		return startAuto(tool, input);
	}

	static void normalizeFollow(JsonObject in) {
		if (!in.has("name") && in.has("player")) {
			in.add("name", in.get("player"));
		}
		if (!in.has("player") && in.has("name")) {
			in.add("player", in.get("name"));
		}
	}

	/** Autopilot からの入口（脳用の割り込み処理をしない）。すぐ結果文を返す。 */
	public synchronized String startAuto(String tool, JsonObject input) {
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
		if ("follow_player".equals(tool)) {
			normalizeFollow(in);
		}
		Skill s = SurvivalSkills.create(tool, owner, this, in);
		if (s == null) {
			s = MotionSkills.create(tool, owner, this, in);
		}
		if (s == null) {
			s = RunSkills.create(tool, owner, this, in);
		}
		if (s == null && "sleep".equals(tool)) {
			s = new SleepSkill(owner, this, in.has("pickup") && in.get("pickup").getAsBoolean());
		}
		return s;
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
			boolean fighting = false;
			try {
				Combat.tick(owner); // 反射の戦い（W2）。戦っている間は今のスキルを一時停止する
				fighting = Combat.isFighting(owner);
			} catch (RuntimeException e) {
				LOGGER.warn("combat tick failed", e);
			}
			if (current != null && !fighting) {
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

	/** 次元移動で作り直されたとき、前の個体の報告済み記録を引き継ぐ。 */
	public synchronized void inheritFrom(SkillRunner prev) {
		reporter.inheritFrom(prev.reporter);
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

	/** 直前に終わったスキルの結果文（「できない: ...」「ダイヤを3個掘った」など）。 */
	public synchronized String lastResult() {
		return lastResult;
	}

	/** 今のスキルの名前（クラス名。Autopilot の見張り用）。何もしていなければ空。 */
	public synchronized String currentName() {
		return current == null || current.finished ? "" : current.getClass().getSimpleName();
	}

	// ---- 覚えておく場所 ----

	public synchronized BlockPos mark(String key) {
		return marks.get(key);
	}

	public synchronized void setMark(String key, BlockPos p) {
		if (p == null) {
			marks.remove(key);
		} else {
			marks.put(key, p.immutable());
		}
	}

	public synchronized Map<String, BlockPos> marks() {
		return new HashMap<>(marks);
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

	/**
	 * 腕に持たせる（終了時に元に戻す）。持ち物に無い物は持たない（見た目だけの偽アイテムは出さない）。
	 * 新しいスキルは Inv.equip で実物を持つこと。
	 */
	void hold(Item item) {
		ItemStack now = owner.getMainHandItem();
		if (!now.isEmpty() && now.is(item)) {
			return;
		}
		if (owner.getInventory().countItem(item) <= 0) {
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
