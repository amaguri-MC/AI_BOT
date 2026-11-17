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
		buildArena(h);
		for (int x : new int[] { 12, 16 }) {
			h.setBlock(x, STAND, 4, Blocks.OAK_LOG);
			h.setBlock(x, STAND + 1, 4, Blocks.OAK_LOG);
		}
		FakeMember m = summon(h, 4.5f, 4.5f, true); // 自動進行は既定のまま（スタート待ち）
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
				h.assertTrue(h.getBlockState(new net.minecraft.core.BlockPos(12, STAND, 4)).is(Blocks.OAK_LOG),
						"スタート前に原木を掘っている");
				m.autopilot().start();
				m.autopilot().start(); // 2回目は何もしない（冪等）
				h.assertTrue(m.autopilot().isStarted(), "start() 後は開始済みのはず");
				st.started = true;
				return;
			}
			if (hasPlacedTable(h)) {
				h.succeed();
			}
		});
	}

	private static boolean hasPlacedTable(GameTestHelper h) {
		for (int x = 1; x < LEN - 1; x++) {
			for (int y = STAND; y < STAND + 4; y++) {
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
		m.setDisplayNameText("TestBot");
		Vec3 dest = h.absoluteVec(new Vec3(20.5, STAND, 4.5));
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, String.format(java.util.Locale.ROOT,
				"tp TestBot %.1f %.1f %.1f", dest.x, dest.y, dest.z));
		h.runAfterDelay(5, () -> {
			h.assertTrue(m.position().distanceTo(dest) < 1.5, "素の名前の /tp で移動したこと: " + m.position());
			server.getCommands().performPrefixedCommand(source, "tag @e[name=TestBot] add aibuddy_test");
			h.assertTrue(m.entityTags().contains("aibuddy_test"), "@e[name=TestBot] で選べること");
			h.succeed();
		});
	}

	/** 死ぬと約3秒後に、同じ人格・名前・スキンで溶け0の新しい体がベッドの所に戻る。 */
	@GameTest(maxTicks = 200)
	public void deadMemberRespawnsWithSameIdentityAfterThreeSeconds(GameTestHelper h) {
		buildArena(h);
		FakeMember m = summon(h, 4.5f, 4.5f, false);
		m.setPersonaId("respawn_test_persona");
		m.setSkinName("test_skin");
		m.setDisplayNameText("Testo");
		m.setMelt(0.6f);
		net.minecraft.core.BlockPos headRel = new net.minecraft.core.BlockPos(14, STAND, 4);
		placeBed(h, headRel);
		m.setBed(h.absolutePos(headRel), h.getLevel().dimension());
		m.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000f);
		h.assertTrue(m.isDeadOrDying(), "致命傷で死にかけになる");
		h.runAfterDelay(100, () -> {
			List<? extends FakeMember> alive = h.getLevel().getEntities(ModEntities.FAKE_MEMBER,
					e -> e.isAlive() && "respawn_test_persona".equals(e.getPersonaId()));
			h.assertTrue(alive.size() == 1, "生きている体がちょうど1つ（二重に湧かない）: " + alive.size());
			FakeMember n = alive.get(0);
			h.assertTrue(n != m, "新しい体に入れ替わっている");
			h.assertTrue("Testo".equals(n.getDisplayNameText()), "表示名が同じ");
			h.assertTrue("test_skin".equals(n.getSkinName()), "スキンが同じ");
			h.assertTrue(n.getMelt() == 0f, "溶けは0に戻る");
			h.assertTrue(n.position().distanceTo(h.absoluteVec(Vec3.atCenterOf(headRel))) < 4.0, "ベッドの近くに戻る: " + n.position());
			h.succeed();
		});
	}

	/** 夜にベッドを持っていれば置いて寝て、そこがリスポーン地点になる。 */
	@GameTest(maxTicks = 300)
	public void memberSleepsInPlacedBedAtNightAndSetsRespawn(GameTestHelper h) {
		buildArena(h);
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set night");
		FakeMember m = summon(h, 4.5f, 4.5f, false);
		m.getInventory().addItem(new ItemStack(Items.RED_BED));
		h.assertTrue(com.amaguri.aibuddy.skill.SleepRules.shouldSleep(m), "夜でベッドを持っていれば寝てよい");
		String r = m.skills().startAuto("sleep", new JsonObject());
		h.assertTrue(!refused(r), "sleep が始まらない: " + r);
		h.runAfterDelay(120, () -> {
			boolean ok = m.isSleeping() && m.getBedPos() != null
					&& m.level().getBlockState(m.getBedPos()).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
			server.getCommands().performPrefixedCommand(source, "time set day");
			h.assertTrue(ok, "寝ていてリスポーン地点が更新されている: sleeping=" + m.isSleeping() + " bed=" + m.getBedPos());
			h.succeed();
		});
	}

	private static void placeBed(GameTestHelper h, net.minecraft.core.BlockPos headRel) {
		var bed = Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
		h.setBlock(headRel.west(), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
		h.setBlock(headRel, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
	}
}
