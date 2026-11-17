package com.amaguri.aibuddy.run;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.skill.RunSkills;
import com.amaguri.aibuddy.skill.SkillRunner;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 自動進行。行動を決めるのはここで、脳（AI）は喋るのとたまの割り込み（ついていく等）だけ。脳を待って止まらない。
 * 召喚直後は「スタート待ち」: 近くの人を見て少しうろつくだけ。start()（脳が「スタート」を聞いた・/aibuddy run on）で始まる。
 * 開始後の毎tick:
 *  1. 見られているか（同じ次元で追跡距離＝160ブロック内にプレイヤー。スペクテイターも「見ている」）を数え、20秒見られていなければ早送り（FastForward）。
 *  2. 段階（Stage）が終わっていれば postEvent して次へ。
 *  3. 手が空いて0.5秒たったら Plan の表から次の行動を SkillRunner に出す（どの段階でも2秒以内）。
 *  4. 動いているスキルが表の制限時間を超えたら打ち切って次の行へ（迷い続けない）。
 */
public final class Autopilot {
	static final Logger LOGGER = LoggerFactory.getLogger("aibuddy-run");

	/** 「見ている」の距離。クライアントに姿が送られる追跡距離（clientTrackingRange チャンク×16＝160）に合わせる。 */
	static double watchRange() {
		try {
			return Math.max(64.0, com.amaguri.aibuddy.entity.ModEntities.FAKE_MEMBER.clientTrackingRange() * 16.0);
		} catch (RuntimeException e) {
			return 160.0;
		}
	}
	static final int UNWATCHED_TO_FF = 20 * 20;
	private static final int IDLE_TO_ACT = 10;
	private static final int MIN_TIMEOUT = 20 * 30;

	private final FakeMember o;
	final FastForward ff;

	private boolean enabled = true;
	/** スタート待ち（召喚直後は true。保存する） */
	private boolean waiting = true;
	/** gametest 用: 早送りを許すか（保存しない） */
	private boolean ffAllowed = true;
	private int waitTicks;
	private Stage stage = Stage.WOOD;
	private int pauseTicks;
	private String pauseWhy = "";
	private int idle;
	private int unwatched;
	private boolean ownSkill;
	private Plan.Row lastRow;
	private int rowTicks;
	private long now;
	private final Map<String, Integer> fails = new HashMap<>();
	private final Map<String, Long> coolUntil = new HashMap<>();
	private String lastDim = "";
	private String lastAction = "";

	// 進行の記憶（保存する）
	private int eyesNeeded = 12;
	private int gravelTries;
	private int foodFails;
	private boolean dragonKilled;
	/** 一度だけやる探索の印（ruin_tried, shipwreck_looted など） */
	private final java.util.Set<String> flags = new java.util.HashSet<>();
	private int oceanCheckedAt = -100000;
	private boolean oceanNear;

	public Autopilot(FakeMember owner) {
		this.o = owner;
		this.ff = new FastForward(owner, this);
	}

	// ================= 公開API（CONTRACT） =================

	public void tick() {
		if (!(o.level() instanceof ServerLevel sl) || o.isRemoved()) {
			return;
		}
		now++;
		if (!enabled || o.isPuddle()) {
			if (ff.active()) {
				ff.stop(false);
			}
			return;
		}
		if (waiting) {
			waitingTick(sl);
			return;
		}
		if (now % 100 == 1 && stage != Stage.DONE) {
			keepLoaded(sl, o.blockPosition());
		}
		trackDimension(sl);
		boolean watched = isWatched(sl);
		unwatched = watched ? 0 : unwatched + 1;
		if (ff.active()) {
			if (watched) {
				ff.stop(true);
			} else {
				ff.tick();
			}
			return;
		}
		if (pauseTicks > 0) {
			if (--pauseTicks == 0) {
				endBrainFollow();
			}
			return;
		}
		if (ffAllowed && unwatched >= UNWATCHED_TO_FF && stage != Stage.DONE && !ff.walkOnly(stage)) {
			ff.start();
			return;
		}
		SkillRunner r = o.skills();
		Ctx c = new Ctx(o, this);
		if (now % 20 == 0 || !r.isBusy()) {
			if (advance(c) && ownSkill && r.isBusy()) {
				// 段階が終わったのに前の段階の行動を続けない
				r.stop();
				ownSkill = false;
			}
		}
		if (r.isBusy()) {
			idle = 0;
			if (ownSkill && lastRow != null && ++rowTicks > Math.max(MIN_TIMEOUT, lastRow.timeout())) {
				LOGGER.info("[run] {} timeout: {}", o.getDisplayNameText(), lastRow.key());
				r.stop();
				fail(lastRow.key());
				ownSkill = false;
			}
			return;
		}
		if (ownSkill) {
			ownSkill = false;
			afterSkill(c, r.lastResult());
		}
		if (++idle >= IDLE_TO_ACT) {
			decide(c);
		}
	}

	/**
	 * スタートの合図（脳が「スタート」等を聞いた・/aibuddy run on）。スタート待ちを解いて自動進行を始める。
	 * すでに始まっていれば何もしない（何度呼んでもよい）。
	 */
	public void start() {
		if (!waiting && enabled) {
			return;
		}
		boolean first = waiting;
		waiting = false;
		enabled = true;
		pauseTicks = 0;
		idle = IDLE_TO_ACT;
		unwatched = 0;
		if (first) {
			if (o.skills().isBusy() && o.skills().currentName().startsWith("Goto")) {
				o.skills().stop(); // 待機中のうろうろをやめる
			}
			LOGGER.info("[run] {} スタート", o.getDisplayNameText());
			o.postEvent("スタートした（" + stage.label + "から）");
		}
	}

	/** スタート済みか（スタート待ちなら false）。 */
	public boolean isStarted() {
		return !waiting;
	}

	/** gametest 用: 早送りを止める（人がいないテスト世界ですぐ早送りにならないように）。保存しない。 */
	public void setFastForwardAllowed(boolean on) {
		ffAllowed = on;
		if (!on && ff.active()) {
			ff.stop(false);
		}
	}

	public void pause(int ticks, String why) {
		pauseTicks = Math.max(pauseTicks, Math.max(0, ticks));
		pauseWhy = why == null ? "" : why;
		if (ff.active()) {
			ff.stop(true);
		}
		ownSkill = false;
	}

	public void resume() {
		if (pauseTicks > 0) {
			endBrainFollow();
		}
		pauseTicks = 0;
		pauseWhy = "";
		idle = IDLE_TO_ACT;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean on) {
		if (enabled && !on) {
			if (ff.active()) {
				ff.stop(false);
			}
			if (ownSkill) {
				o.skills().stop();
				ownSkill = false;
			}
		}
		enabled = on;
		idle = IDLE_TO_ACT;
	}

	public String stageName() {
		return waiting ? "スタート待ち" : stage.label;
	}

	/** 「ブレイズロッド集め（4/7）」のような今の目標。 */
	public String goalText() {
		if (!(o.level() instanceof ServerLevel)) {
			return stage.label;
		}
		if (waiting) {
			return "スタート待ち（合図があったら" + stage.label + "から始める）";
		}
		String p = Plan.progress(stage, new Ctx(o, this));
		String base = stage.label + (p.isEmpty() ? "" : "（" + p + "）");
		if (!enabled) {
			return base + " ※自動進行オフ";
		}
		if (ff.active()) {
			return base + " ※早送り中";
		}
		if (pauseTicks > 0) {
			return base + " ※一時停止中（" + pauseWhy + "）";
		}
		return base;
	}

	public boolean isFastForwarding() {
		return ff.active();
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putBoolean("Enabled", enabled);
		t.putString("Stage", stage.id);
		t.putInt("Pause", pauseTicks);
		t.putString("PauseWhy", pauseWhy);
		t.putInt("EyesNeeded", eyesNeeded);
		t.putInt("GravelTries", gravelTries);
		t.putInt("FoodFails", foodFails);
		t.putBoolean("Waiting", waiting);
		t.putString("Flags", String.join(",", flags));
		t.putBoolean("DragonKilled", dragonKilled);
		t.putString("LastDim", lastDim);
		t.putInt("Unwatched", unwatched);
		ff.save(t);
		CompoundTag m = new CompoundTag();
		for (Map.Entry<String, BlockPos> e : o.skills().marks().entrySet()) {
			m.putLong(e.getKey(), e.getValue().asLong());
		}
		t.put("Marks", m);
		return t;
	}

	public void load(CompoundTag t) {
		if (t == null) {
			return;
		}
		enabled = t.getBooleanOr("Enabled", true);
		Stage s = Stage.byId(t.getStringOr("Stage", "wood"));
		stage = s == null ? Stage.WOOD : s;
		pauseTicks = t.getIntOr("Pause", 0);
		pauseWhy = t.getStringOr("PauseWhy", "");
		eyesNeeded = t.getIntOr("EyesNeeded", 12);
		gravelTries = t.getIntOr("GravelTries", 0);
		foodFails = t.getIntOr("FoodFails", 0);
		// v0.2 の保存データ（Waiting なし）はもう走っていたので待たない
		waiting = t.getBooleanOr("Waiting", false);
		flags.clear();
		for (String f : t.getStringOr("Flags", "").split(",")) {
			if (!f.isBlank()) {
				flags.add(f);
			}
		}
		if (t.getBooleanOr("RuinChecked", false)) {
			flags.add("ruin_tried");
		}
		if (t.getBooleanOr("RuinLooted", false)) {
			flags.add("ruin_looted");
		}
		dragonKilled = t.getBooleanOr("DragonKilled", false);
		lastDim = t.getStringOr("LastDim", "");
		unwatched = t.getIntOr("Unwatched", 0);
		ff.load(t);
		CompoundTag m = t.getCompound("Marks").orElse(null);
		if (m != null) {
			for (String k : m.keySet()) {
				o.skills().setMark(k, BlockPos.of(m.getLongOr(k, 0L)));
			}
		}
	}

	// ================= そのほか公開（コマンド・他担当用） =================

	public Stage stage() {
		return stage;
	}

	/** /aibuddy run skipto: 前の段階で手に入るはずの物を持たせて、その段階から始める。 */
	public void skipTo(Stage target) {
		if (ff.active()) {
			ff.stop(false);
		}
		o.skills().stop();
		ownSkill = false;
		for (Stage s : Stage.values()) {
			if (s.ordinal() >= target.ordinal()) {
				break;
			}
			ff.grant(s, 1.0);
		}
		setStage(target, "skipto");
	}

	/** 状態の1行説明（/aibuddy run status 用）。 */
	public String statusLine() {
		return o.getDisplayNameText() + ": " + goalText() + " / " + o.skills().status()
				+ (lastAction.isEmpty() ? "" : " / 直前の判断: " + lastAction)
				+ (ff.active() ? " / 早送り " + ff.progressText() : "");
	}

	// ================= 内部 =================

	/** 脳が始めた「ついていく」は一時停止の期限で止めて、自分の進行に戻る（60秒の約束）。 */
	private void endBrainFollow() {
		SkillRunner r = o.skills();
		if (!ownSkill && r.isBusy() && r.currentName().startsWith("Follow")) {
			r.stop();
			LOGGER.info("[run] {} ついていく期限切れ → 進行に戻る", o.getDisplayNameText());
		}
		idle = IDLE_TO_ACT;
	}

	int eyesNeeded() {
		return eyesNeeded;
	}

	boolean flintGaveUp() {
		return gravelTries >= 4;
	}

	boolean foodGaveUp() {
		return foodFails >= 3;
	}

	boolean flag(String f) {
		return flags.contains(f);
	}

	void setFlag(String f) {
		flags.add(f);
	}

	/** 半径96に海洋バイオームがあるか（16ブロックおきに見る。30秒ごとに見直す）。 */
	boolean oceanNear(Ctx c) {
		if (now - oceanCheckedAt < 600) {
			return oceanNear;
		}
		oceanCheckedAt = (int) now;
		oceanNear = false;
		if (!c.overworld()) {
			return false;
		}
		BlockPos me = o.blockPosition();
		for (int dx = -96; dx <= 96 && !oceanNear; dx += 16) {
			for (int dz = -96; dz <= 96; dz += 16) {
				BlockPos p = new BlockPos(me.getX() + dx, 62, me.getZ() + dz);
				if (c.l.hasChunkAt(p) && c.l.getBiome(p).is(net.minecraft.tags.BiomeTags.IS_OCEAN)) {
					oceanNear = true;
					break;
				}
			}
		}
		return oceanNear;
	}

	/** スタート待ち: 近くの人を見て、ときどき2〜3ブロックだけうろつく。進行・早送りはしない。 */
	private void waitingTick(ServerLevel sl) {
		SkillRunner r = o.skills();
		if (r.isBusy()) {
			return; // 脳が頼んだ「ついていく」などはそのまま
		}
		ServerPlayer p = new Ctx(o, this).nearestPlayer(16);
		if (p != null) {
			o.getLookControl().setLookAt(p, 30f, 30f);
		}
		if (++waitTicks % 160 == 0 && o.getRandom().nextInt(3) == 0) {
			BlockPos me = o.blockPosition();
			int dx = o.getRandom().nextInt(5) - 2;
			int dz = o.getRandom().nextInt(5) - 2;
			r.startAuto("goto", Plan.pos(me.offset(dx, 0, dz)));
		}
	}

	boolean dragonKilled() {
		return dragonKilled;
	}

	void setDragonKilled() {
		dragonKilled = true;
	}

	int unwatchedTicks() {
		return unwatched;
	}

	void resetUnwatched() {
		unwatched = 0;
	}

	void setStage(Stage s, String why) {
		if (s != stage) {
			LOGGER.info("[run] {} stage {} -> {} ({})", o.getDisplayNameText(), stage.id, s.id, why);
		}
		stage = s;
		fails.clear();
		coolUntil.clear();
		idle = IDLE_TO_ACT;
	}

	/** 段階が終わっていれば postEvent して次へ（いくつでも）。進んだら true。 */
	boolean advance(Ctx c) {
		boolean moved = false;
		if (stage == Stage.DRAGON && !dragonKilled && c.end()) {
			var fight = c.l.getDragonFight();
			if (fight != null && fight.hasPreviouslyKilledDragon() && c.l.getDragons().isEmpty()) {
				dragonKilled = true;
			}
		}
		for (int guard = 0; guard < Stage.values().length && stage != Stage.DONE && Plan.done(stage, c); guard++) {
			String ev = Plan.doneEvent(stage, c);
			if (!ev.isEmpty()) {
				o.postEvent(ev);
			}
			setStage(stage.next(), "done");
			moved = true;
		}
		return moved;
	}

	private void decide(Ctx c) {
		SkillRunner r = o.skills();
		List<Plan.Row> rows = Plan.rows(stage);
		for (Plan.Row row : rows) {
			Long until = coolUntil.get(row.key());
			if (until != null && until > now) {
				continue;
			}
			boolean hit;
			try {
				hit = row.when().test(c);
			} catch (RuntimeException e) {
				LOGGER.warn("[run] row {} test failed", row.key(), e);
				continue;
			}
			if (!hit) {
				continue;
			}
			if (row.tool().startsWith("#jump:")) {
				Stage to = Stage.byId(row.tool().substring(6));
				if (to != null && to != stage) {
					lastAction = row.why();
					setStage(to, row.why());
					return;
				}
				continue;
			}
			String res;
			try {
				res = r.startAuto(row.tool(), row.in() == null ? new com.google.gson.JsonObject() : row.in().apply(c));
			} catch (RuntimeException e) {
				res = "できない: " + e.getMessage();
			}
			if (res.startsWith("開始")) {
				ownSkill = true;
				lastRow = row;
				rowTicks = 0;
				idle = 0;
				lastAction = row.why();
				return;
			}
			LOGGER.debug("[run] {} {} -> {}", o.getDisplayNameText(), row.key(), res);
			fail(row.key());
		}
		// 全部だめ: 少し間を置いて（idle を戻して）また見る。探検も失敗する所なら跳ねて待つ
		idle = IDLE_TO_ACT - 20;
		r.startAuto("emote", Plan.j("kind", "jump"));
	}

	private void afterSkill(Ctx c, String res) {
		if (lastRow == null) {
			return;
		}
		String key = lastRow.key();
		if (isFailure(res)) {
			fail(key);
			if (key.equals("hunt") && stage == Stage.FOOD) {
				foodFails++;
			}
			if (key.equals("fill") && res.contains("あと")) {
				// 額縁にはめるアイが足りない → パール集めに戻る
				int more = parseInt(res.substring(res.indexOf("あと") + 2));
				eyesNeeded = c.eyes() + Math.max(1, more);
				setStage(Stage.PEARLS, "アイ不足");
			}
		} else {
			fails.remove(key);
			coolUntil.remove(key);
		}
		if (key.equals("gravel") && !c.has(Items.FLINT)) {
			gravelTries++;
		}
		if (key.equals("dragon") && res.contains("倒した")) {
			dragonKilled = true;
		}
	}

	static boolean isFailure(String res) {
		return res == null || res.isEmpty() || res.contains("できない") || res.contains("失敗") || res.contains("見つから")
				|| res.contains("たどり着けな") || res.contains("切り上げ") || res.contains("いなくなった") || res.contains("止めた")
				|| res.contains("疲れた") || res.contains("近づけない");
	}

	private static int parseInt(String s) {
		int n = 0;
		for (char ch : s.toCharArray()) {
			if (ch >= '0' && ch <= '9') {
				n = n * 10 + (ch - '0');
			} else if (n > 0) {
				break;
			}
		}
		return n;
	}

	private void fail(String key) {
		int n = fails.merge(key, 1, Integer::sum);
		coolUntil.put(key, now + Math.min(20L * 60, 100L * n));
	}

	/** 同じ次元で追跡距離（160）内にプレイヤー（スペクテイターも含む）がいれば「見られている」。 */
	boolean isWatched(ServerLevel sl) {
		return watchedAt(sl, o.position());
	}

	/** その次元のその場所が、誰かの追跡距離内か（早送りの飛び先の確認にも使う）。 */
	static boolean watchedAt(ServerLevel sl, net.minecraft.world.phys.Vec3 at) {
		double r = watchRange();
		for (ServerPlayer p : sl.getServer().getPlayerList().getPlayers()) {
			if (p.level() == sl && p.position().distanceToSqr(at) <= r * r) {
				return true;
			}
		}
		return false;
	}

	/** 次元が変わったら、くぐったゲートの場所を覚える（帰り道）。 */
	private void trackDimension(ServerLevel sl) {
		String d = sl.dimension().identifier().toString();
		if (lastDim.equals(d)) {
			return;
		}
		String prev = lastDim;
		lastDim = d;
		if (prev.isEmpty()) {
			return;
		}
		if (sl.dimension().equals(Level.NETHER)) {
			o.skills().setMark(RunSkills.MARK_PORTAL_NETHER, o.blockPosition());
		} else if (sl.dimension().equals(Level.OVERWORLD) && prev.endsWith("the_nether")) {
			o.skills().setMark(RunSkills.MARK_PORTAL_OW, o.blockPosition());
		}
		idle = IDLE_TO_ACT;
	}

	/** 誰も近くにいなくても自分のいるチャンクを動かし続ける（早送り・進行が止まらないように）。 */
	static void keepLoaded(ServerLevel sl, BlockPos p) {
		// UNVERIFIED: TicketType.PORTAL は約300tickで切れ、読み込み＋シミュレーション＋次元を起こしておく（半径3でエンティティも動く）
		sl.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(p), 3);
	}
}
