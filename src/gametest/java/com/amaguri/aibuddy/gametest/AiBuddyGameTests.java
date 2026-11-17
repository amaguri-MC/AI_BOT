package com.amaguri.aibuddy.gametest;

import java.util.List;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;

/**
 * 実機で言われた不満（最初から強い・歩きが遅い・素手でも掘れる）を二度と起こさないためのサーバー gametest。
 * (5) は自動進行のスタート待ちと序盤の手順（原木→作業台）。
 * 舞台は buildArena（48x6x9、床は土、周りはガラス）。座標はすべて GameTestHelper の相対座標。
 * 実行: ./gradlew runGameTest
 */
public class AiBuddyGameTests {
	// 構造物ファイルは使わず（既定の空構造物 8x8x8）、テスト内で上空 y=20 に 48x6x9 の舞台を setBlock で作る。
	// 高さをずらすのは、横に並ぶ他テストの舞台と重ならないため。
	private static final int FLOOR = 20;
	private static final int STAND = FLOOR + 1;
	/** 速度テスト専用の高さ（他テストの舞台と重ならない） */
	private static final int SPEED_FLOOR = 60;
	/** スタート待ちテスト専用の高さ */
	private static final int START_FLOOR = 100;
	/** 寝るテスト専用の高さ（隣のテストの舞台がベッドを上書きしないように） */
	private static final int SLEEP_FLOOR = 160;
	private static final int WAKE_FLOOR = 200;
	/** 鉄を掘るテスト専用の高さ（隣の舞台が石の壁・落ちた鉄に重ならないように） */
	private static final int MINE_FLOOR = 240;
	/** リスポーンのテスト専用の高さ（隣の舞台がベッドを上書きすると世界の初期地点に戻ってしまう） */
	private static final int RESPAWN_BED_FLOOR = 30;
	private static final int LEN = 48;
	private static final int WID = 9;

	/** 床は土、周りと天井はガラス、中は空気。 */
	private static void buildArena(GameTestHelper h) {
		buildArena(h, FLOOR);
	}

	/**
	 * floor を変えると別の高さに舞台を作る。48ブロックの舞台は隣のテストの区画に食い込むため、
	 * 並んで走る他テストの舞台（ガラスの壁）が走路を塞いでしまう。速度テストは専用の高さを使う。
	 */
	private static void buildArena(GameTestHelper h, int FLOOR) {
		// 舞台は隣のチャンクまで伸びる。端のチャンクが entity ticking でないと mob が宙で凍るので、確実に読み込んでおく。
		net.minecraft.core.BlockPos ca = h.absolutePos(new net.minecraft.core.BlockPos(0, FLOOR, 0));
		net.minecraft.core.BlockPos cb = h.absolutePos(new net.minecraft.core.BlockPos(LEN - 1, FLOOR, WID - 1));
		for (int cx = (ca.getX() >> 4) - 1; cx <= (cb.getX() >> 4) + 1; cx++) {
			for (int cz = (ca.getZ() >> 4) - 1; cz <= (cb.getZ() >> 4) + 1; cz++) {
				h.getLevel().setChunkForced(cx, cz, true); // UNVERIFIED: ServerLevel.setChunkForced(int,int,boolean)
			}
		}
		for (int x = 0; x < LEN; x++) {
			for (int y = 0; y < 6; y++) {
				for (int z = 0; z < WID; z++) {
					boolean edge = x == 0 || x == LEN - 1 || z == 0 || z == WID - 1 || y == 5;
					net.minecraft.world.level.block.Block b = y == 0 ? Blocks.DIRT : edge ? Blocks.GLASS : Blocks.AIR;
					h.setBlock(x, FLOOR + y, z, b);
				}
			}
		}
	}

	// ---- helpers ----

	private static FakeMember summon(GameTestHelper h, float x, float z, boolean autopilot) {
		return summon(h, x, z, autopilot, FLOOR);
	}

	private static FakeMember summon(GameTestHelper h, float x, float z, boolean autopilot, int floor) {
		FakeMember m = h.spawn(ModEntities.FAKE_MEMBER, x, (float) (floor + 1), z);
		if (!autopilot) {
			// 指示なしで勝手に動く Autopilot を止め、スキルだけを試す。
			// UNVERIFIED: FakeMember.autopilot() / Autopilot.setEnabled(boolean) は CONTRACT W3 の署名。まだ実装が無いかもしれない
			m.autopilot().setEnabled(false);
		}
		return m;
	}

	private static JsonObject json(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v instanceof Number n) {
				o.addProperty((String) kv[i], n);
			} else {
				o.addProperty((String) kv[i], String.valueOf(v));
			}
		}
		return o;
	}

	private static boolean refused(String startResult) {
		return startResult == null || startResult.startsWith("できない");
	}

	private static int count(FakeMember m, Item item) {
		int n = 0;
		for (int i = 0; i < m.getInventory().getContainerSize(); i++) {
			ItemStack s = m.getInventory().getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** 持ち物＋足元に落ちている分。 */
	private static int countIncludingDropped(GameTestHelper h, FakeMember m, Item item) {
		int n = count(m, item);
		// getEntitiesOfClass は絶対座標の AABB を取る（getBounds() は絶対）
		List<ItemEntity> drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, h.getBounds());
		for (ItemEntity e : drops) {
			if (e.getItem().is(item)) {
				n += e.getItem().getCount();
			}
		}
		return n;
	}

	private static Vec3 rel(GameTestHelper h, FakeMember m) {
		return h.relativeVec(m.position());
	}

	// ---- (1) 召喚直後の持ち物が空 ----

	@GameTest(maxTicks = 80)
	public void summonedMemberHasEmptyInventory(GameTestHelper h) {
		buildArena(h);
		FakeMember m = summon(h, 4.5f, 4.5f, true); // Autopilot は既定のまま（召喚直後はスタート待ちで何もしない）
		h.runAfterDelay(40, () -> {
			h.assertTrue(m.getInventory().isEmpty(), "召喚直後・40tick後でも持ち物が空であること");
			for (EquipmentSlot slot : EquipmentSlot.VALUES) {
				if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) {
					continue;
				}
				h.assertTrue(m.getItemBySlot(slot).isEmpty(), "装備スロット " + slot.getName() + " が空であること");
			}
			h.succeed();
		});
	}

	// ---- (2) 素手の石は遅くてドロップ無し、木のツルハシなら丸石 ----

	private enum Phase { BARE_START, BARE_WAIT, PICK_START, PICK_WAIT }

	@GameTest(maxTicks = 900)
	public void mineStoneBareHandsVsWoodenPickaxe(GameTestHelper h) {
		final int bareX = 8;
		final int pickX = 8;
		buildArena(h);
		h.setBlock(bareX, STAND, 4, Blocks.STONE);
		FakeMember m = summon(h, 3.5f, 4.5f, false);
		var st = new Object() {
			Phase phase = Phase.BARE_START;
			long startedAt;
		};
		h.onEachTick(() -> {
			long now = h.getTick();
			switch (st.phase) {
				case BARE_START -> {
					String r = m.skills().start("mine", json("block", "stone", "count", 1));
					st.startedAt = now;
					st.phase = Phase.BARE_WAIT;
					if (refused(r)) {
						// 素手では掘らない実装でもよい（速く・タダで掘れなければ目的は満たす）。次へ。
						h.assertTrue(h.getBlockState(new net.minecraft.core.BlockPos(bareX, STAND, 4)).is(Blocks.STONE),
								"断ったのに石が消えている: " + r);
						st.phase = Phase.PICK_START;
					}
				}
				case BARE_WAIT -> {
					long el = now - st.startedAt;
					boolean gone = !h.getBlockState(new net.minecraft.core.BlockPos(bareX, STAND, 4)).is(Blocks.STONE);
					// バニラの素手は石に約150tick。歩く時間を差し引いても90tick未満で壊れたら速すぎ。
					if (gone && el < 90) {
						h.fail("素手で石が " + el + " tick で壊れた（速すぎ。バニラは約150tick）");
					}
					if (gone || el > 400 || !m.skills().isBusy()) {
						h.assertTrue(countIncludingDropped(h, m, Items.COBBLESTONE) == 0,
								"素手で石を掘って丸石が出た（適正道具でないのでドロップ無しのはず）");
						m.skills().stop();
						st.phase = Phase.PICK_START;
					}
				}
				case PICK_START -> {
					m.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
					h.setBlock(pickX, STAND, 4, Blocks.STONE);
					String r = m.skills().start("mine", json("block", "stone", "count", 1));
					h.assertTrue(!refused(r), "木のツルハシ持ちで mine が始まらない: " + r);
					st.startedAt = now;
					st.phase = Phase.PICK_WAIT;
				}
				case PICK_WAIT -> {
					long el = now - st.startedAt;
					// バニラの木のツルハシは石に約23tick。歩きと拾いを含めて 200tick 以内に丸石が手に入ること。
					if (count(m, Items.COBBLESTONE) >= 1) {
						h.succeed();
					} else if (el > 200) {
						h.fail("木のツルハシで 200tick 経っても丸石が持ち物に入らない（status=" + m.skills().status() + "）");
					}
				}
			}
		});
	}

	/** 壁（石）の中に埋まった鉄鉱石2個を、2つの間を往復せずに掘り切れること。 */
	@GameTest(maxTicks = 1500)
	public void mineTwoBuriedIronOresWithoutPingPong(GameTestHelper h) {
		buildArena(h, MINE_FLOOR);
		for (int x = 10; x <= 16; x++) {
			for (int y = (MINE_FLOOR + 1); y <= (MINE_FLOOR + 1) + 3; y++) {
				for (int z = 1; z <= 7; z++) {
					h.setBlock(x, y, z, Blocks.STONE);
				}
			}
		}
		h.setBlock(12, (MINE_FLOOR + 1) + 1, 4, Blocks.IRON_ORE);
		h.setBlock(14, (MINE_FLOOR + 1) + 1, 3, Blocks.IRON_ORE);
		FakeMember m = summon(h, 3.5f, 4.5f, false, MINE_FLOOR);
		m.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		String r = m.skills().start("mine", json("block", "iron", "count", 2));
		h.assertTrue(!refused(r), "石のツルハシ持ちで mine iron が始まらない: " + r);
		var st = new Object() {
			double travelled;
			Vec3 last;
		};
		h.onEachTick(() -> {
			Vec3 p = rel(h, m);
			if (st.last != null) {
				st.travelled += p.distanceTo(st.last);
			}
			st.last = p;
			if (h.getTick() % 100 == 0 || h.getTick() == 1400) {
				com.amaguri.aibuddy.AiBuddyMod.LOGGER.info("[mine-test] t={} pos={} travelled={} iron={} status={}", h.getTick(), p, st.travelled, count(m, Items.RAW_IRON), m.skills().status());
			}
			if (h.getTick() == 1400) {
				h.fail("鉄を掘り切れない: " + m.skills().status() + " travelled=" + st.travelled);
			}
			if (count(m, Items.RAW_IRON) >= 2) {
				// 目標までの直線は十数ブロック。往復していれば移動距離が大きく膨らむ。
				h.assertTrue(st.travelled < 60, "鉄を掘るまでの移動距離が長すぎる（往復？）: " + st.travelled);
				h.succeed();
			}
		});
	}

	// ---- (3) 平らな40ブロックでダッシュ速度 5〜7.5 ブロック/秒 ----

	@GameTest(maxTicks = 500)
	public void sprintSpeedOnFlatGround(GameTestHelper h) {
		final double startX = 2.5;
		final double targetX = 42.5;
		final int floor = SPEED_FLOOR;
		buildArena(h, floor);
		FakeMember m = summon(h, (float) startX, 4.5f, false, floor);
		Vec3 target = h.absoluteVec(new Vec3(targetX, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var st = new Object() {
			long t0 = -1;
			long t1 = -1;
		};
		h.onEachTick(() -> {
			double x = rel(h, m).x;
			if (h.getTick() % 20 == 0) {
				System.out.println("[speedtest] t=" + h.getTick() + " rel=" + rel(h, m) + " sprint=" + m.isSprinting()
						+ " onGround=" + m.onGround() + " navDone=" + m.getNavigation().isDone() + " status=" + m.skills().status());
			}
			// 加速と到着の減速を除いて、x=8 → x=40 の32ブロックを測る
			if (st.t0 < 0 && x >= 8.0) {
				st.t0 = h.getTick();
			}
			if (st.t0 >= 0 && st.t1 < 0 && x >= 40.0) {
				st.t1 = h.getTick();
				double seconds = (st.t1 - st.t0) / 20.0;
				double speed = 32.0 / seconds;
				h.assertTrue(speed >= 5.0 && speed <= 7.5,
						String.format("ダッシュ速度 %.2f ブロック/秒（5.0〜7.5 のはず）", speed));
				h.succeed();
			}
		});
	}

	// ---- (4) 原木4から 作業台 と 木のツルハシ を作れる ----

	@GameTest(maxTicks = 400)
	public void craftTableAndWoodenPickaxeFromFourLogs(GameTestHelper h) {
		buildArena(h);
		FakeMember m = summon(h, 4.5f, 4.5f, false);
		m.getInventory().addItem(new ItemStack(Items.OAK_LOG, 4));
		var st = new Object() {
			int step = 0;
			long startedAt;
		};
		h.onEachTick(() -> {
			long now = h.getTick();
			if (st.step == 0) {
				// UNVERIFIED: craft{item,count} は原木→板材の中間素材を自動で作る（CONTRACT W1 の想定）。item は英語ID
				String r = m.skills().start("craft", json("item", "crafting_table", "count", 1));
				h.assertTrue(!refused(r), "作業台の craft が始まらない: " + r);
				st.startedAt = now;
				st.step = 1;
			} else if (st.step == 1 && !m.skills().isBusy()) {
				// 作業台は持ち物にあるか、すでに置かれている（3x3 のレシピ用に自分で置く実装でもよい）
				String r = m.skills().start("craft", json("item", "wooden_pickaxe", "count", 1));
				h.assertTrue(!refused(r), "木のツルハシの craft が始まらない: " + r);
				st.startedAt = now;
				st.step = 2;
			} else if (st.step == 2 && !m.skills().isBusy()) {
				h.assertTrue(count(m, Items.WOODEN_PICKAXE) >= 1,
						"木のツルハシが持ち物に無い（結果: " + m.skills().status() + "）");
				boolean table = count(m, Items.CRAFTING_TABLE) >= 1 || hasPlacedTable(h);
				h.assertTrue(table, "作業台が持ち物にも周囲にも無い");
				h.succeed();
			}
			if (st.step > 0 && now - st.startedAt > 250) {
				h.fail("craft が 250tick で終わらない（step=" + st.step + " status=" + m.skills().status() + "）");
			}
		});
	}

	// ---- (5) 召喚直後は動かず（スタート待ち）、start() で 原木→作業台 に進む ----

	@GameTest(maxTicks = 1200)
	public void waitsForStartThenLogsToCraftingTable(GameTestHelper h) {
		// 専用の高さに舞台を作る（FLOOR=20 の舞台は隣のテストと重なり、置いた原木が隣の舞台の空気で消されることがあった）。
		// 床の下も3段埋める（1段の床を掘り抜くと20ブロック落ちて死ぬのはテスト舞台だけの事故）。
		final int floor = START_FLOOR;
		final int stand = floor + 1;
		buildArena(h, floor);
		for (int x = 0; x < LEN; x++) {
			for (int z = 0; z < WID; z++) {
				for (int y = 1; y <= 3; y++) {
					h.setBlock(x, floor - y, z, Blocks.STONE);
				}
			}
		}
		// 原木は自分の区画（x<8）の中に3本＋予備1本
		h.setBlock(6, stand, 2, Blocks.OAK_LOG);
		h.setBlock(6, stand + 1, 2, Blocks.OAK_LOG);
		h.setBlock(6, stand, 6, Blocks.OAK_LOG);
		h.setBlock(6, stand + 1, 6, Blocks.OAK_LOG);
		FakeMember m = summon(h, 2.5f, 4.5f, true, floor); // 自動進行は既定のまま（スタート待ち）
		m.autopilot().setFastForwardAllowed(false); // 人のいないテスト世界で早送りしない
		var st = new Object() {
			boolean started;
		};
		h.onEachTick(() -> {
			long now = h.getTick();
			if (!st.started) {
				if (now < 60) {
					return;
				}
				h.assertTrue(!m.autopilot().isStarted(), "召喚直後はスタート待ちのはず");
				h.assertTrue(m.getInventory().isEmpty(), "スタート前に物を集めている");
				h.assertTrue(h.getBlockState(new net.minecraft.core.BlockPos(6, stand, 2)).is(Blocks.OAK_LOG),
						"スタート前に原木を掘っている");
				m.autopilot().start();
				m.autopilot().start(); // 2回目は何もしない（冪等）
				h.assertTrue(m.autopilot().isStarted(), "start() 後は開始済みのはず");
				st.started = true;
				return;
			}
			if (now % 20 == 0) {
				StringBuilder inv = new StringBuilder();
				for (int i = 0; i < m.getInventory().getContainerSize(); i++) {
					ItemStack s = m.getInventory().getItem(i);
					if (!s.isEmpty()) {
						inv.append(s.getItem()).append('x').append(s.getCount()).append(' ');
					}
				}
				System.out.println("[starttest] t=" + now + " stage=" + m.autopilot().stageName() + " goal=" + m.autopilot().goalText()
						+ " status=" + m.skills().status() + " last=" + m.skills().lastResult() + " inv=[" + inv + "] rel=" + rel(h, m)
						+ " hand=" + m.getMainHandItem() + " log62=" + h.getBlockState(new net.minecraft.core.BlockPos(6, stand, 2)) + " alive=" + m.isAlive()
						+ " line=" + m.autopilot().statusLine());
			}
			if (!m.isAlive()) {
				h.fail("メンバーが死んだ（status=" + m.skills().status() + "）");
			}
			if (hasPlacedTable(h, stand)) {
				h.succeed();
			}
		});
	}

	private static boolean hasPlacedTable(GameTestHelper h) {
		return hasPlacedTable(h, STAND);
	}

	private static boolean hasPlacedTable(GameTestHelper h, int stand) {
		for (int x = 1; x < LEN - 1; x++) {
			for (int y = stand; y < stand + 4; y++) {
				for (int z = 1; z < WID - 1; z++) {
					if (h.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).is(Blocks.CRAFTING_TABLE)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** `/tp TestBot x y z` のように素の名前・@e[name=] で FakeMember を指定できる（Mixin と customName）。 */
	@GameTest(maxTicks = 100)
	public void vanillaCommandsCanTargetFakeMemberByName(GameTestHelper h) {
		buildArena(h);
		FakeMember m = summon(h, 4.5f, 4.5f, false);
		// 再試行・並行テストで同名の体が残っていると別の体を掴むので、毎回ユニークな名前にする
		final String name = "TpBot" + Long.toString(System.nanoTime() % 100000000L);
		m.setDisplayNameText(name);
		Vec3 dest = h.absoluteVec(new Vec3(20.5, STAND, 4.5));
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, String.format(java.util.Locale.ROOT,
				"tp " + name + " %.1f %.1f %.1f", dest.x, dest.y, dest.z));
		h.runAfterDelay(10, () -> {
			h.assertTrue(m.position().distanceTo(dest) < 2.5, "素の名前の /tp で移動したこと: " + m.position());
			server.getCommands().performPrefixedCommand(source, "tag @e[name=" + name + "] add aibuddy_test");
			h.assertTrue(m.entityTags().contains("aibuddy_test"), "@e[name=" + name + "] で選べること");
			m.discard();
			h.succeed();
		});
	}

	/** 死ぬと約3秒後に、同じ人格・名前・スキンで溶け0の新しい体がベッドの所に戻る。 */
	@GameTest(maxTicks = 200)
	public void deadMemberRespawnsWithSameIdentityAfterThreeSeconds(GameTestHelper h) {
		buildArena(h, RESPAWN_BED_FLOOR);
		FakeMember m = summon(h, 4.5f, 4.5f, false, RESPAWN_BED_FLOOR);
		final String persona = "respawn_test_" + Long.toString(System.nanoTime() % 100000000L); // 再試行の残骸と混ざらない
		m.setPersonaId(persona);
		m.setSkinName("test_skin");
		m.setDisplayNameText("Testo");
		m.setMelt(0.6f);
		net.minecraft.core.BlockPos headRel = new net.minecraft.core.BlockPos(14, RESPAWN_BED_FLOOR + 1, 4);
		placeBed(h, headRel);
		m.setBed(h.absolutePos(headRel), h.getLevel().dimension());
		m.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000f);
		h.assertTrue(m.isDeadOrDying(), "致命傷で死にかけになる");
		h.runAfterDelay(100, () -> {
			List<? extends FakeMember> alive = h.getLevel().getEntities(ModEntities.FAKE_MEMBER,
					e -> e.isAlive() && persona.equals(e.getPersonaId()));
			h.assertTrue(alive.size() == 1, "生きている体がちょうど1つ（二重に湧かない）: " + alive.size());
			FakeMember n = alive.get(0);
			h.assertTrue(n != m, "新しい体に入れ替わっている");
			h.assertTrue("Testo".equals(n.getDisplayNameText()), "表示名が同じ");
			h.assertTrue("test_skin".equals(n.getSkinName()), "スキンが同じ");
			h.assertTrue(n.getMelt() < 0.02f, "溶けは0に戻る（リスポーン後の経過tick分の溶けだけ）: " + n.getMelt());
			h.assertTrue(n.position().distanceTo(h.absoluteVec(Vec3.atCenterOf(headRel))) < 4.0, "ベッドの近くに戻る: " + n.position());
			n.discard();
			h.succeed();
		});
	}

	/** 夜にベッドを持っていれば置いて寝て、そこがリスポーン地点になる。 */
	@GameTest(maxTicks = 300)
	public void memberSleepsInPlacedBedAtNightAndSetsRespawn(GameTestHelper h) {
		buildArena(h, SLEEP_FLOOR);
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set night");
		FakeMember m = summon(h, 4.5f, 4.5f, false, SLEEP_FLOOR);
		m.getInventory().addItem(new ItemStack(Items.BED.pick(net.minecraft.world.item.DyeColor.RED)));
		h.assertTrue(com.amaguri.aibuddy.skill.SleepRules.shouldSleep(m), "夜でベッドを持っていれば寝てよい");
		String r = m.skills().startAuto("sleep", new JsonObject());
		h.assertTrue(!refused(r), "sleep が始まらない: " + r);
		// 寝るとすぐリスポーン地点が更新され、約3秒（60tick）で起きる。寝ている間（起きる前）に、ベッドがリスポーン地点になったことを確かめる
		boolean[] seen = { false };
		h.onEachTick(() -> {
			long t = h.getTick();
			seen[0] |= m.isSleeping() && m.getBedPos() != null
					&& m.level().getBlockState(m.getBedPos()).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
			if (seen[0] && t >= 30) { // 朝にするのは他の寝るテストのボットが寝付いてから
				server.getCommands().performPrefixedCommand(source, "time set day");
				h.succeed();
			} else if (t >= 50) {
				server.getCommands().performPrefixedCommand(source, "time set day");
				h.fail("寝ていてリスポーン地点が更新されている: sleeping=" + m.isSleeping() + " bed=" + m.getBedPos() + " status=" + m.skills().status());
			}
		});
	}

	/** 本物のプレイヤーが寝ていなければ、数秒横になってリスポーン地点を更新したら起きる（一晩1回の印も付く）。 */
	@GameTest(maxTicks = 300)
	public void memberWakesAfterFewSecondsWhenNoRealPlayerSleeps(GameTestHelper h) {
		buildArena(h, WAKE_FLOOR);
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set night");
		FakeMember m = summon(h, 4.5f, 4.5f, false, WAKE_FLOOR);
		m.getInventory().addItem(new ItemStack(Items.BED.pick(net.minecraft.world.item.DyeColor.RED)));
		String r = m.skills().startAuto("sleep", new JsonObject());
		h.assertTrue(!refused(r), "sleep が始まらない: " + r);
		h.runAfterDelay(160, () -> {
			boolean woke = !m.isSleeping() && m.getBedPos() != null && m.getSleptDay() >= 0;
			server.getCommands().performPrefixedCommand(source, "time set day");
			h.assertTrue(woke, "約3秒で起きて、ベッドと印が残る: sleeping=" + m.isSleeping() + " bed=" + m.getBedPos() + " day=" + m.getSleptDay());
			h.succeed();
		});
	}

	/** 寝た状態で保存して読み込んでも、寝たまま固まらず起きる。 */
	@GameTest(maxTicks = 100)
	public void sleepingMemberWakesAfterSaveAndLoad(GameTestHelper h) {
		buildArena(h);
		net.minecraft.core.BlockPos headRel = new net.minecraft.core.BlockPos(14, STAND, 4);
		placeBed(h, headRel);
		FakeMember m = summon(h, 13.5f, 4.5f, false);
		m.startSleeping(h.absolutePos(headRel));
		h.assertTrue(m.isSleeping(), "前提: 寝ている");
		var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
				net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
		m.saveWithoutId(out);
		var tag = out.buildResult();
		m.discard();
		FakeMember n = ModEntities.FAKE_MEMBER.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
		n.load(net.minecraft.world.level.storage.TagValueInput.create(
				net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
		h.getLevel().addFreshEntity(n);
		h.runAfterDelay(10, () -> {
			boolean awake = !n.isSleeping();
			n.discard();
			h.assertTrue(awake, "読込後の初tickで起きる");
			h.succeed();
		});
	}

	// ---- (6) 深い水の中では泳ぎ姿勢になる（直立のまま移動しない） ----

	private static final int SWIM_FLOOR = 140;
	private static final int BOAT_FLOOR = 180;

	private static void forceChunks(GameTestHelper h, int floor, int len) {
		net.minecraft.core.BlockPos ca = h.absolutePos(new net.minecraft.core.BlockPos(0, floor, 0));
		net.minecraft.core.BlockPos cb = h.absolutePos(new net.minecraft.core.BlockPos(len - 1, floor, WID - 1));
		for (int cx = (ca.getX() >> 4) - 1; cx <= (cb.getX() >> 4) + 1; cx++) {
			for (int cz = (ca.getZ() >> 4) - 1; cz <= (cb.getZ() >> 4) + 1; cz++) {
				h.getLevel().setChunkForced(cx, cz, true);
			}
		}
	}

	/**
	 * 水路。x が waterFrom..waterTo の間は水（深さ depth、水面は floor の高さ）、その外は陸（床は floor）。
	 * 上は開けてある（屋根なし）。両端と横はガラスの壁。
	 */
	private static void buildCanal(GameTestHelper h, int floor, int len, int waterFrom, int waterTo, int depth) {
		buildCanal(h, floor, len, waterFrom, waterTo, depth, 0);
	}

	/** endLift > 0 なら、水の向こう側（x > waterTo）の陸を endLift ブロック高い崖にする。 */
	private static void buildCanal(GameTestHelper h, int floor, int len, int waterFrom, int waterTo, int depth, int endLift) {
		forceChunks(h, floor, len);
		for (int x = 0; x < len; x++) {
			for (int z = 0; z < WID; z++) {
				boolean wallZ = z == 0 || z == WID - 1;
				boolean water = x >= waterFrom && x <= waterTo;
				int bottom = water ? floor - depth : floor;
				for (int y = floor - depth; y <= floor + 5; y++) {
					net.minecraft.world.level.block.Block b;
					boolean wallX = x == 0 || x == len - 1;
					if (wallZ || wallX) {
						b = Blocks.GLASS;
					} else if (endLift > 0 && x > waterTo && y <= floor + endLift) {
						b = Blocks.DIRT;
					} else if (y < bottom) {
						b = Blocks.AIR;
					} else if (y == bottom && !water) {
						b = Blocks.DIRT;
					} else if (water && y == bottom) {
						b = Blocks.DIRT;
					} else if (water && y <= floor) {
						b = Blocks.WATER;
					} else {
						b = Blocks.AIR;
					}
					h.setBlock(x, y, z, b);
				}
			}
		}
	}

	@GameTest(maxTicks = 300)
	public void swimsInSwimmingPoseInDeepWater(GameTestHelper h) {
		final int floor = SWIM_FLOOR;
		buildCanal(h, floor, 20, 1, 18, 5);
		FakeMember m = h.spawn(ModEntities.FAKE_MEMBER, 4.5f, (float) (floor - 2), 4.5f);
		m.autopilot().setEnabled(false);
		Vec3 target = h.absoluteVec(new Vec3(17.5, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		h.onEachTick(() -> {
			long t = h.getTick();
			if (t % 10 == 0) {
				System.out.println("[swimtest] t=" + t + " rel=" + rel(h, m) + " pose=" + m.getPose() + " swimming=" + m.isSwimming()
						+ " inWater=" + m.isInWater() + " status=" + m.skills().status());
			}
			if (t >= 20 && m.isInWater() && m.getPose() == net.minecraft.world.entity.Pose.SWIMMING && m.isVisuallySwimming()) {
				double y = rel(h, m).y;
				h.assertTrue(y > floor - 3.0, "底を歩いている y=" + y);
				h.succeed();
			} else if (t >= 200) {
				h.assertTrue(false, "泳ぎ姿勢にならない pose=" + m.getPose() + " swimming=" + m.isSwimming() + " inWater=" + m.isInWater());
			}
		});
	}

	// ---- (7) 30ブロックの水路は、板材5からボートを作って渡る ----

	@GameTest(maxTicks = 900)
	public void crossesLongCanalByBoat(GameTestHelper h) {
		final int floor = BOAT_FLOOR;
		buildCanal(h, floor, 48, 4, 36, 3); // 水面33ブロック
		FakeMember m = summon(h, 2.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 5));
		Vec3 target = h.absoluteVec(new Vec3(42.5, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var st = new Object() {
			boolean rode;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			if (m.isPassenger()) {
				st.rode = true;
			}
			if (t % 20 == 0) {
				System.out.println("[boattest] t=" + t + " rel=" + rel(h, m) + " passenger=" + m.isPassenger() + " inWater=" + m.isInWater()
						+ " status=" + m.skills().status());
			}
			Vec3 p = rel(h, m);
			if (st.rode && p.x > 40.0 && !m.isPassenger()) {
				h.succeed();
			} else if (t >= 850) {
				h.assertTrue(false, "ボートで渡れない rode=" + st.rode + " rel=" + p + " status=" + m.skills().status());
			}
		});
	}

	// ---- (8) 崖の岸に着いても漂い続けない（上限内で降りて泳ぎに切り替え、ボートを回収する） ----

	private static final int CLIFF_FLOOR = 220;
	private static final int SHALLOW_FLOOR = 260;
	private static final int EXIT_FLOOR = 300;

	@GameTest(maxTicks = 900)
	public void doesNotDriftForeverAtCliffShore(GameTestHelper h) {
		final int floor = CLIFF_FLOOR;
		buildCanal(h, floor, 48, 4, 36, 3, 4); // 向こう岸は4段の崖
		FakeMember m = summon(h, 2.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 5));
		Vec3 target = h.absoluteVec(new Vec3(42.5, floor + 5, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var st = new Object() {
			boolean rode;
			long rodeAt = -1;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			if (m.isPassenger()) {
				st.rode = true;
				if (st.rodeAt < 0) {
					st.rodeAt = t;
				}
			}
			if (t % 20 == 0) {
				System.out.println("[clifftest] t=" + t + " rel=" + rel(h, m) + " passenger=" + m.isPassenger() + " inWater=" + m.isInWater()
						+ " status=" + m.skills().status());
			}
			var boats = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.vehicle.boat.AbstractBoat.class,
					m.getBoundingBox().inflate(60, 30, 60));
			if (st.rode && !m.isPassenger() && boats.isEmpty() && m.getInventory().countItem(Items.OAK_BOAT) >= 1) {
				h.assertTrue(t - st.rodeAt < 700, "降りるまでに時間がかかりすぎ " + (t - st.rodeAt));
				h.succeed();
			} else if (t >= 800) {
				h.assertTrue(false, "崖の岸で漂い続けている rode=" + st.rode + " boats=" + boats.size() + " rel=" + rel(h, m));
			}
		});
	}

	// ---- (9) 水から上がると立ち姿勢に戻る ----

	@GameTest(maxTicks = 500)
	public void returnsToStandingAfterLeavingWater(GameTestHelper h) {
		final int floor = EXIT_FLOOR;
		buildCanal(h, floor, 20, 1, 8, 4); // 水は x=1..8、その先は陸
		FakeMember m = h.spawn(ModEntities.FAKE_MEMBER, 4.5f, (float) (floor - 2), 4.5f);
		m.autopilot().setEnabled(false);
		Vec3 target = h.absoluteVec(new Vec3(14.5, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var st = new Object() {
			boolean sawSwim;
			int landed;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			if (m.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
				st.sawSwim = true;
			}
			if (t % 20 == 0) {
				System.out.println("[exittest] t=" + t + " rel=" + rel(h, m) + " pose=" + m.getPose() + " swimming=" + m.isSwimming()
						+ " inWater=" + m.isInWater() + " status=" + m.skills().status());
			}
			boolean onLand = !m.isInWater() && m.onGround() && rel(h, m).x > 10.0;
			if (st.sawSwim && onLand) {
				st.landed++;
				if (st.landed >= 10) {
					h.assertTrue(m.getPose() == net.minecraft.world.entity.Pose.STANDING && !m.isSwimming(),
							"陸に上がっても泳ぎ姿勢のまま pose=" + m.getPose() + " swimming=" + m.isSwimming());
					h.succeed();
				}
			} else if (t >= 450) {
				h.assertTrue(false, "陸に上がれない sawSwim=" + st.sawSwim + " rel=" + rel(h, m));
			}
		});
	}

	// ---- (10) 深さ1の浅瀬では泳がない（歩いて渡る） ----

	@GameTest(maxTicks = 400)
	public void doesNotSwimInOneBlockDeepWater(GameTestHelper h) {
		final int floor = SHALLOW_FLOOR;
		buildCanal(h, floor, 22, 4, 14, 1); // 深さ1の水たまりが x=4..14
		FakeMember m = summon(h, 2.5f, 4.5f, false, floor);
		Vec3 target = h.absoluteVec(new Vec3(19.5, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var st = new Object() {
			boolean wasInWater;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			if (m.isInWater()) {
				st.wasInWater = true;
			}
			h.assertTrue(m.getPose() != net.minecraft.world.entity.Pose.SWIMMING && !m.isSwimming(),
					"浅瀬で泳ぎ姿勢になった rel=" + rel(h, m) + " pose=" + m.getPose());
			if (rel(h, m).x > 17.0) {
				h.assertTrue(st.wasInWater, "浅瀬を通っていない（テストの前提）");
				h.succeed();
			} else if (t >= 350) {
				h.assertTrue(false, "浅瀬を渡れない rel=" + rel(h, m) + " status=" + m.skills().status());
			}
		});
	}

	// ---- (11) 無駄に戦わない: 囲まれたら柱で逃げる／狙っていない敵には近づかない／クリーパーからは離れる ----

	private static final int EVADE_FLOOR = 320;
	private static final int IDLE_FLOOR = 335;
	private static final int CREEPER_FLOOR = 350;

	/** 床は土、横はガラス、天井は高さ height のガラス（柱を積んでも頭がつかえない）。 */
	private static void buildHall(GameTestHelper h, int floor, int len, int height) {
		forceChunks(h, floor, len);
		for (int x = 0; x < len; x++) {
			for (int z = 0; z < WID; z++) {
				for (int y = 0; y <= height; y++) {
					boolean edge = x == 0 || x == len - 1 || z == 0 || z == WID - 1 || y == height;
					net.minecraft.world.level.block.Block b = y == 0 ? Blocks.DIRT : edge ? Blocks.GLASS : Blocks.AIR;
					h.setBlock(x, floor + y, z, b);
				}
			}
		}
	}

	private static void noBurn(net.minecraft.world.entity.Mob z) {
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // 日光で燃えない
		z.setDropChance(EquipmentSlot.HEAD, 0.0f);
	}

	@GameTest(maxTicks = 400)
	public void climbsPillarWhenThreeZombiesTargetIt(GameTestHelper h) {
		final int floor = EVADE_FLOOR;
		buildHall(h, floor, 24, 10);
		FakeMember m = summon(h, 12.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 12));
		var zs = new java.util.ArrayList<net.minecraft.world.entity.monster.zombie.Zombie>();
		float[][] at = { { 5.5f, 2.5f }, { 5.5f, 6.5f }, { 19.5f, 4.5f } };
		for (float[] a : at) {
			var z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, a[0], (float) (floor + 1), a[1]);
			noBurn(z);
			zs.add(z);
		}
		var st = new Object() {
			long topAt = -1;
			float hpTop;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			for (var z : zs) {
				if (z.isAlive()) {
					z.setTarget(m); // 3体に狙われている状況を作る
				}
			}
			double y = rel(h, m).y;
			if (t % 20 == 0) {
				System.out.println("[evadetest] t=" + t + " rel=" + rel(h, m) + " hp=" + m.getHealth() + " status=" + m.skills().status());
			}
			if (st.topAt < 0 && y >= floor + 1 + 2.7 && m.onGround()) {
				st.topAt = t;
				st.hpTop = m.getHealth();
			}
			if (st.topAt >= 0 && t >= st.topAt + 100) {
				h.assertTrue(m.getHealth() >= st.hpTop - 0.01, "柱の上でもダメージを受け続けた hp " + st.hpTop + " → " + m.getHealth());
				h.assertTrue(rel(h, m).y >= floor + 1 + 2.5, "柱から降りてしまった y=" + rel(h, m).y);
				h.succeed();
			} else if (t >= 300) {
				h.assertTrue(false, "柱で安全地帯に上がれない top=" + st.topAt + " rel=" + rel(h, m) + " hp=" + m.getHealth());
			}
		});
	}

	@GameTest(maxTicks = 300)
	public void doesNotApproachZombieThatIsNotTargetingIt(GameTestHelper h) {
		final int floor = IDLE_FLOOR;
		buildHall(h, floor, 24, 8);
		FakeMember m = summon(h, 8.5f, 4.5f, false, floor);
		var z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 13.5f, (float) (floor + 1), 4.5f);
		noBurn(z);
		z.setNoAi(true); // 動かず、こちらも狙わない
		var st = new Object() {
			double min = 99;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			st.min = Math.min(st.min, m.distanceTo(z));
			if (t >= 120) {
				h.assertTrue(st.min >= 4.0, "狙っていないゾンビに近づいた 最小距離 " + st.min);
				h.assertTrue(z.getHealth() >= z.getMaxHealth() - 0.01, "狙っていないゾンビを殴った hp=" + z.getHealth());
				h.succeed();
			}
		});
	}

	@GameTest(maxTicks = 300)
	public void backsAwayFromCreeper(GameTestHelper h) {
		final int floor = CREEPER_FLOOR;
		buildHall(h, floor, 40, 8);
		FakeMember m = summon(h, 14.5f, 4.5f, false, floor);
		var c = h.spawn(net.minecraft.world.entity.EntityTypes.CREEPER, 10.5f, (float) (floor + 1), 4.5f);
		h.onEachTick(() -> {
			long t = h.getTick();
			if (c.isAlive()) {
				c.setTarget(m);
			}
			if (t % 20 == 0) {
				System.out.println("[creepertest] t=" + t + " dist=" + m.distanceTo(c) + " hp=" + m.getHealth() + " creeperHp=" + c.getHealth());
			}
			if (t == 24) { // 後退は1回あたり最大6ブロック。止まったあとの追いつかれる前に測る
				h.assertTrue(c.isAlive() && c.getHealth() >= c.getMaxHealth() - 0.01, "クリーパーを殴った");
				h.assertTrue(m.getHealth() >= m.getMaxHealth() - 0.01, "クリーパーに爆破された hp=" + m.getHealth());
				h.assertTrue(m.distanceTo(c) >= 6.0, "クリーパーから離れない 距離 " + m.distanceTo(c));
				h.succeed();
			}
		});
	}

	// ---- (12) 避難の安全確認・往復しない・元の行動に戻る ----

	private static final int LAVA_FLOOR = 40;
	private static final int SPIDER_FLOOR = 80;
	private static final int RESUME_FLOOR = 120;

	@GameTest(maxTicks = 300)
	public void doesNotFallIntoLavaWhileEvading(GameTestHelper h) {
		final int floor = LAVA_FLOOR;
		buildHall(h, floor, 26, 7);
		// 5x5 の土の足場（x=10..14, z=2..6）以外は、床の高さを溶岩にする
		for (int x = 1; x < 25; x++) {
			for (int z = 1; z < WID - 1; z++) {
				boolean platform = x >= 10 && x <= 14 && z >= 2 && z <= 6;
				if (!platform) {
					h.setBlock(x, floor, z, Blocks.LAVA);
				}
			}
		}
		FakeMember m = summon(h, 12.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD)); // ブロックは持たない: 走って逃げるしかない
		m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		var zs = new java.util.ArrayList<net.minecraft.world.entity.monster.zombie.Zombie>();
		float[][] at = { { 10.5f, 3.5f }, { 14.5f, 5.5f } };
		for (float[] a : at) {
			var z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, a[0], (float) (floor + 1), a[1]);
			noBurn(z);
			zs.add(z);
		}
		h.onEachTick(() -> {
			long t = h.getTick();
			for (var z : zs) {
				if (z.isAlive()) {
					z.setTarget(m);
				}
			}
			h.assertTrue(m.isAlive() && !m.isInLava() && !m.isOnFire(), "溶岩に落ちた/死んだ rel=" + rel(h, m) + " hp=" + m.getHealth());
			if (t >= 200) {
				h.succeed();
			}
		});
	}

	/** L字の幅1通路の舞台（溶岩の上）専用の高さ */
	private static final int CORNER_FLOOR = 280;
	/** 昆布の深い水の舞台は、他の舞台（y=20 以上）より下 */
	private static final int KELP_FLOOR = 2;

	/** 溶岩に囲まれた L字の幅1通路の角で敵に狙われても、角の切り欠きへ下がって落ちず、戦える。 */
	@GameTest(maxTicks = 400)
	public void fightsAtCornerOfOneWideLShapedPathWithoutFalling(GameTestHelper h) {
		final int floor = CORNER_FLOOR;
		buildHall(h, floor, 26, 7);
		// 通路: x=5..14 の z=2（横の腕）と、x=14 の z=2..6（縦の腕）。角は (14,2)。それ以外の床は溶岩
		for (int x = 1; x < 25; x++) {
			for (int z = 1; z < WID - 1; z++) {
				boolean path = (z == 2 && x >= 5 && x <= 14) || (x == 14 && z >= 2 && z <= 6);
				if (!path) {
					h.setBlock(x, floor, z, Blocks.LAVA);
				}
			}
		}
		FakeMember m = summon(h, 14.5f, 2.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.IRON_SWORD));
		var z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 7.5f, (float) (floor + 1), 2.5f);
		noBurn(z);
		h.onEachTick(() -> {
			long t = h.getTick();
			if (z.isAlive()) {
				z.setTarget(m);
			}
			h.assertTrue(m.isAlive() && !m.isInLava() && !m.isOnFire(), "L字の角で溶岩に落ちた/死んだ rel=" + rel(h, m) + " hp=" + m.getHealth());
			if (!z.isAlive() || z.getHealth() < z.getMaxHealth() - 0.01) {
				if (t >= 100) {
					h.succeed();
				}
			} else if (t >= 360) {
				h.fail("L字の角でゾンビと戦えない rel=" + rel(h, m) + " zombieHp=" + z.getHealth() + " status=" + m.skills().status());
			}
		});
	}

	/** 昆布の生えた深い水（10段）の底からでも浮き上がって息をし、溺れない。 */
	@GameTest(maxTicks = 420)
	public void doesNotDrownInDeepWaterWithKelp(GameTestHelper h) {
		final int floor = KELP_FLOOR;
		buildHall(h, floor, 7, 13);
		final int top = floor + 10;
		for (int x = 1; x <= 5; x++) {
			for (int z = 1; z < WID - 1; z++) {
				for (int y = floor + 1; y <= top; y++) {
					// 昆布は水の中の植物（水を含む）。下から積み、上端は KELP、その上は水
					net.minecraft.world.level.block.Block b = y < top - 2 ? Blocks.KELP_PLANT : y == top - 2 ? Blocks.KELP : Blocks.WATER;
					if ((x + z) % 3 == 0 && y == floor + 1) {
						b = Blocks.SEAGRASS;
					} else if ((x + z) % 3 == 0) {
						b = Blocks.WATER;
					}
					h.setBlock(x, y, z, b);
				}
			}
		}
		FakeMember m = summon(h, 3.5f, 4.5f, false, floor);
		var st = new Object() {
			boolean surfaced;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			if (m.getY() >= h.absolutePos(new net.minecraft.core.BlockPos(0, top - 1, 0)).getY()) {
				st.surfaced = true;
			}
			h.assertTrue(m.isAlive() && m.getHealth() >= m.getMaxHealth() - 0.01,
					"昆布の水で溺れた rel=" + rel(h, m) + " hp=" + m.getHealth() + " air=" + m.getAirSupply());
			if (t >= 380) {
				h.assertTrue(st.surfaced, "水面まで浮き上がらない rel=" + rel(h, m) + " air=" + m.getAirSupply());
				h.succeed();
			}
		});
	}

	@GameTest(maxTicks = 500)
	public void fightsBackAfterRunningFromTwoSpidersWithoutBlocks(GameTestHelper h) {
		final int floor = SPIDER_FLOOR;
		buildHall(h, floor, 40, 6);
		FakeMember m = summon(h, 20.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.IRON_SWORD)); // ブロック0個
		var sp = new java.util.ArrayList<net.minecraft.world.entity.monster.spider.Spider>();
		float[][] at = { { 8.5f, 3.5f }, { 8.5f, 5.5f } };
		for (float[] a : at) {
			sp.add(h.spawn(net.minecraft.world.entity.EntityTypes.SPIDER, a[0], (float) (floor + 1), a[1]));
		}
		h.onEachTick(() -> {
			long t = h.getTick();
			for (var s2 : sp) {
				if (s2.isAlive()) {
					s2.setTarget(m);
				}
			}
			if (t % 40 == 0) {
				System.out.println("[spidertest] t=" + t + " rel=" + rel(h, m) + " hp=" + m.getHealth() + " spiderHp="
						+ sp.get(0).getHealth() + "," + sp.get(1).getHealth());
			}
			boolean hit = sp.stream().anyMatch(s2 -> !s2.isAlive() || s2.getHealth() < s2.getMaxHealth() - 0.01);
			if (hit) {
				h.succeed(); // 走って逃げ続けず、いつかは反撃に切り替わる
			} else if (t >= 450 || !m.isAlive()) {
				h.assertTrue(false, "反撃に切り替わらない rel=" + rel(h, m) + " hp=" + m.getHealth());
			}
		});
	}

	@GameTest(maxTicks = 900)
	public void resumesOriginalSkillAfterEvading(GameTestHelper h) {
		final int floor = RESUME_FLOOR;
		buildHall(h, floor, 40, 10);
		FakeMember m = summon(h, 2.5f, 4.5f, false, floor);
		m.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 12));
		Vec3 target = h.absoluteVec(new Vec3(34.5, floor + 1, 4.5));
		String r = m.skills().start("goto", json("x", target.x, "y", target.y, "z", target.z));
		h.assertTrue(!refused(r), "goto が始まらない: " + r);
		var zs = new java.util.ArrayList<net.minecraft.world.entity.monster.zombie.Zombie>();
		var st = new Object() {
			boolean elevated;
		};
		h.onEachTick(() -> {
			long t = h.getTick();
			Vec3 p = rel(h, m);
			if (t == 15) {
				float[][] off = { { 4f, 0f }, { -3f, 2f }, { 0f, -3f } };
				for (float[] a : off) {
					var z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, (float) p.x + a[0], (float) (floor + 1), (float) p.z + a[1]);
					noBurn(z);
					zs.add(z);
				}
			}
			if (t >= 15 && t < 120) {
				for (var z : zs) {
					if (z.isAlive()) {
						z.setTarget(m);
					}
				}
			}
			if (t == 120) {
				for (var z : zs) {
					z.discard(); // 敵が去る → 避難が終わる
				}
			}
			if (p.y >= floor + 1 + 2.5) {
				st.elevated = true;
			}
			if (t % 40 == 0) {
				System.out.println("[resumetest] t=" + t + " rel=" + p + " status=" + m.skills().status());
			}
			if (t > 130 && !m.skills().isBusy()) {
				h.assertTrue(st.elevated, "避難していない（前提）");
				String res = m.skills().lastResult();
				h.assertTrue(!res.isEmpty() && !res.startsWith("止めた") && !res.startsWith("失敗") && p.x > 30.0,
						"元の行動の結果が返らない lastResult=" + res + " rel=" + p);
				h.succeed();
			} else if (t >= 850) {
				h.assertTrue(false, "元の行動が終わらない status=" + m.skills().status() + " rel=" + p);
			}
		});
	}

	private static void placeBed(GameTestHelper h, net.minecraft.core.BlockPos headRel) {
		var bed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
		h.setBlock(headRel.west(), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
		h.setBlock(headRel, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
	}
}
