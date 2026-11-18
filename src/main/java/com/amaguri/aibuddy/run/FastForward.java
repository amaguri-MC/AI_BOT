package com.amaguri.aibuddy.run;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.skill.RunSkills;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * 見ていない所の早送り。
 * 開始: 自動進行オンで、同じ次元の64ブロック内にプレイヤー（スペクテイターも「見ている」扱い）が20秒いない。
 * 終了: 誰かが追跡距離（160ブロック）内に入った瞬間（その段階の進み具合に応じた分だけ物を足して、本当に動く状態に戻る）。
 * 進め方: 段階ごとの標準時間（Stage.ffSeconds、合計約12分）だけ待ったら、その段階で手に入るはずの物を持ち物に足し、
 * 次の場所（ネザー・ネザー要塞・要塞のエンドポータル・エンド）へテレポート／次元移動する。
 * 早送り中は無敵（見ていない所で事故死しない）。スキルは止める。
 * 飛び先の160ブロック内に人がいたら飛ばない: 早送りをやめ、その段階は本当に歩く（walkOnly）。
 */
final class FastForward {
	private final FakeMember o;
	private final Autopilot ap;
	private boolean active;
	private int ticks;
	private Stage ticksStage;
	/** 飛び先に人がいたので本当に歩く段階（この段階のあいだは早送りしない） */
	private Stage walkOnly;

	FastForward(FakeMember o, Autopilot ap) {
		this.o = o;
		this.ap = ap;
	}

	boolean active() {
		return active;
	}

	boolean walkOnly(Stage s) {
		return walkOnly == s;
	}

	/** 飛び先 at が誰かに見られていたら、飛ばずに早送りをやめてこの段階を本当に歩く。止めたら true。 */
	private boolean blocked(Stage s, ServerLevel to, BlockPos at) {
		if (to == null || at == null || !Autopilot.watchedAt(to, Vec3.atCenterOf(at))) {
			return false;
		}
		walkOnly = s;
		ticks = 0;
		stop(false);
		Autopilot.LOGGER.info("[run] {} 飛び先に人がいるので早送りせず歩く: {}", o.getDisplayNameText(), s.label);
		return true;
	}

	String progressText() {
		int total = Math.max(1, ap.stage().ffSeconds * 20);
		return ticks * 100 / total + "%";
	}

	void start() {
		if (walkOnly != null && walkOnly != ap.stage()) {
			walkOnly = null;
		}
		if (ap.stage() == Stage.DONE || walkOnly == ap.stage()) {
			return;
		}
		active = true;
		if (ticksStage != ap.stage()) {
			ticks = 0;
			ticksStage = ap.stage();
		}
		o.skills().stop();
		o.getNavigation().stop();
		o.setInvulnerable(true);
		Autopilot.LOGGER.info("[run] {} 早送り開始: {}", o.getDisplayNameText(), ap.stage().label);
	}

	/** partial=true なら進んだ割合だけ物を足す（人が来たとき）。 */
	void stop(boolean partial) {
		if (!active) {
			return;
		}
		active = false;
		o.setInvulnerable(false);
		if (partial && ticksStage == ap.stage()) {
			double frac = Math.min(1.0, ticks / (double) Math.max(1, ap.stage().ffSeconds * 20));
			grant(ap.stage(), frac);
			ticks = 0;
		}
		ap.resetUnwatched();
		Autopilot.LOGGER.info("[run] {} 早送り終了: {}", o.getDisplayNameText(), ap.stage().label);
	}

	void tick() {
		if (!(o.level() instanceof ServerLevel)) {
			return;
		}
		o.getNavigation().stop();
		Stage s = ap.stage();
		if (s == Stage.DONE) {
			stop(false);
			return;
		}
		if (ticksStage != s) {
			ticksStage = s;
			ticks = 0;
		}
		if (walkOnly != null && walkOnly != s) {
			walkOnly = null;
		}
		ticks++;
		if (ticks < s.ffSeconds * 20) {
			return;
		}
		ticks = 0;
		complete(s);
	}

	/** 段階を終わらせる: 物を足す → 場所を移す → postEvent → 次の段階。テレポートしたらこの個体は消える。 */
	private void complete(Stage s) {
		Ctx c = new Ctx(o, ap);
		ServerLevel l = c.l;
		Stage next = s.next();
		switch (s) {
			case PORTAL -> {
				BlockPos portal = c.mark(RunSkills.MARK_PORTAL_OW);
				if (!c.nether()) {
					ServerLevel neth = l.getServer().getLevel(Level.NETHER);
					if (blocked(s, neth, BlockPos.containing(o.getX() / 8, 70, o.getZ() / 8))) {
						return;
					}
					grant(s, 1.0);
					if (portal == null || !l.hasChunkAt(portal) || !l.getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
						portal = RunSkills.instantNetherPortal(o);
						o.postEvent("ゲート作った 座標 " + portal.getX() + " " + portal.getY() + " " + portal.getZ());
					}
					finishStage(s, c, next);
					// バニラのゲートの行き先計算でネザーへ（向こう側のゲートも作られる）
					Entity moved = RunSkills.usePortalNow(o, portal);
					if (moved == null) {
						jump(l.getServer().getLevel(Level.NETHER), BlockPos.containing(o.getX() / 8, 70, o.getZ() / 8));
					}
					return;
				}
			}
			case BLAZE -> {
				ServerLevel nether = l.getServer().getLevel(Level.NETHER);
				BlockPos from = c.nether() ? o.blockPosition() : BlockPos.containing(o.getX() / 8, 64, o.getZ() / 8);
				BlockPos f = c.mark(RunSkills.MARK_FORTRESS);
				if (f == null) {
					f = RunSkills.findFortress(nether, from);
				}
				if (f != null) {
					if (blocked(s, nether, f)) {
						return;
					}
					grant(s, 1.0);
					o.skills().setMark(RunSkills.MARK_FORTRESS, f);
					finishStage(s, c, next);
					jump(nether, new BlockPos(f.getX(), 64, f.getZ()));
					return;
				}
			}
			case STRONGHOLD -> {
				ServerLevel ow = l.getServer().overworld();
				BlockPos from = c.nether() ? BlockPos.containing(o.getX() * 8, 64, o.getZ() * 8) : o.blockPosition();
				BlockPos sh = c.mark(RunSkills.MARK_STRONGHOLD);
				if (sh == null) {
					sh = RunSkills.findStronghold(ow, from);
				}
				if (sh != null) {
					if (blocked(s, ow, sh)) {
						return;
					}
					grant(s, 1.0);
					o.skills().setMark(RunSkills.MARK_STRONGHOLD, sh);
					RunSkills.PortalRoom room = RunSkills.portalRoom(ow, sh);
					BlockPos dest;
					if (room != null) {
						o.skills().setMark(RunSkills.MARK_FRAME, room.center());
						dest = room.stand();
					} else {
						dest = RunSkills.safeSpot(ow, sh, 8);
					}
					finishStage(s, c, next);
					jump(ow, dest);
					return;
				}
			}
			case FILL_PORTAL -> {
				BlockPos sh = c.mark(RunSkills.MARK_STRONGHOLD);
				if (c.overworld() && sh != null) {
					RunSkills.PortalRoom room = RunSkills.portalRoom(l, sh);
					if (room != null) {
						if (blocked(s, l.getServer().getLevel(Level.END), new BlockPos(100, 49, 0))) {
							return;
						}
						long missing = room.frames().stream()
								.filter(p -> l.getBlockState(p).is(Blocks.END_PORTAL_FRAME)
										&& !l.getBlockState(p).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)).count();
						ensure(Items.ENDER_EYE, (int) missing);
						RunSkills.fillFramesInstant(o, room.frames());
						BlockPos center = room.center();
						finishStage(s, c, next);
						if (l.getBlockState(center).is(Blocks.END_PORTAL) && RunSkills.usePortalNow(o, center) != null) {
							return;
						}
						jump(l.getServer().getLevel(Level.END), new BlockPos(100, 49, 0));
						return;
					}
				}
			}
			case DRAGON -> {
				ServerLevel end = l.getServer().getLevel(Level.END);
				if (end != null && !c.end()) {
					// エンドにいないのに倒した扱いにしない。エンドへ行く段階に戻す
					ap.setStage(Stage.FILL_PORTAL, "エンドにいない");
					return;
				}
				if (end != null) {
					for (EnderDragon d : end.getDragons()) {
						d.kill(end);
					}
				}
				ap.setDragonKilled();
				o.postEvent("エンドラ倒した");
			}
			default -> {
			}
		}
		if (s == Stage.DRAGON && !ap.dragonKilled()) {
			return;
		}
		grant(s, 1.0);
		finishStage(s, c, next);
	}

	private void finishStage(Stage s, Ctx c, Stage next) {
		if (ap.stage() != s) {
			return;
		}
		String ev = Plan.doneEvent(s, c);
		if (!ev.isEmpty()) {
			o.postEvent(ev);
		}
		ap.setStage(next, "早送り");
		ticks = 0;
		ticksStage = next;
		if (next == Stage.DONE) {
			stop(false);
		}
	}

	/** 次元をまたいでも安全な場所へ。テレポート前に行き先のチャンクを起こしておく。 */
	private void jump(ServerLevel to, BlockPos near) {
		if (to == null) {
			return;
		}
		Autopilot.keepLoaded(to, near);
		BlockPos p = RunSkills.safeSpot(to, near, 12);
		// UNVERIFIED: 別次元なら teleport は新しい個体を作り restoreFrom で保存データ（Autopilot の状態込み）を写す
		o.teleport(new TeleportTransition(to, Vec3.atBottomCenterOf(p), Vec3.ZERO, o.getYRot(), 0f, TeleportTransition.DO_NOTHING));
	}

	// ---- 物を足す ----

	/** 段階 s で手に入るはずの物を、frac（0..1）の割合で足す。すでに持っている分は足さない。 */
	void grant(Stage s, double frac) {
		if (!(o.level() instanceof ServerLevel)) {
			return;
		}
		Ctx c = new Ctx(o, ap);
		boolean full = frac >= 0.999;
		switch (s) {
			case WOOD -> {
				if (full) {
					ensure(Items.OAK_LOG, 3);
				}
			}
			case TABLE -> {
				if (full && !c.tableNear(64) && c.pickTier() < 1) {
					// 早送り中に作業台を置いたことにする（足元の横に本当に置く）
					o.getInventory().removeItemType(Items.OAK_LOG, 1);
					placeTableNow();
				}
			}
			case WOODEN_PICK -> {
				if (full && c.pickTier() < 1) {
					// 原木2本＝板材8: 木のツルハシ3＋棒8本ぶん4（棒は石ツルハシ・石斧に4本使う）。板材1が残る
					add(Items.WOODEN_PICKAXE, 1);
					o.getInventory().removeItemType(Items.OAK_LOG, 2);
					ensure(Items.STICK, 6);
					ensure(Items.OAK_PLANKS, 1);
				}
			}
			case STONE -> {
				if (full) {
					if (c.pickTier() < 2) {
						add(Items.STONE_PICKAXE, 1);
					}
					if (c.axeTier() < 2) {
						add(Items.STONE_AXE, 1);
					}
				}
			}
			case MORE_WOOD -> {
				if (full) {
					ensure(Items.OAK_LOG, 5);
				}
			}
			case LOOT -> {
				if (full) {
					if (c.buckets() < 2 && c.n(Items.OBSIDIAN) < 10) {
						if (!c.has(Items.WATER_BUCKET)) {
							add(Items.WATER_BUCKET, 1);
						}
						if (c.buckets() < 2) {
							add(Items.BUCKET, 1);
						}
					}
					if (!Plan.hasIgniter(c)) {
						add(Items.FLINT_AND_STEEL, 1);
					}
					ensure(Items.BREAD, 3); // 村・沈没船のチェストで拾うはずのパン
				} else {
					ensure(Items.IRON_INGOT, (int) Math.floor(Plan.ironNeed(c) * frac));
				}
			}
			case FOOD -> {
				int want = (int) Math.floor(6 * frac) - c.cooked();
				if (want > 0) {
					add(Items.COOKED_BEEF, want);
				}
			}
			case BLAZE -> {
				int want = (int) Math.floor(14 * frac) - c.blazeUnits();
				if (want > 0) {
					add(Items.BLAZE_ROD, (want + 1) / 2);
				}
			}
			case PEARLS -> {
				int want = (int) Math.floor(ap.eyesNeeded() * frac) - c.pearls() - c.eyes();
				if (want > 0) {
					add(Items.ENDER_PEARL, want);
				}
			}
			case EYES -> {
				if (full) {
					int k = ap.eyesNeeded() - c.eyes();
					if (k > 0) {
						o.getInventory().removeItemType(Items.ENDER_PEARL, Math.min(k, c.pearls()));
						int powderUse = Math.min(k, c.powder());
						o.getInventory().removeItemType(Items.BLAZE_POWDER, powderUse);
						int rodUse = Math.min(c.rods(), (k - powderUse + 1) / 2);
						o.getInventory().removeItemType(Items.BLAZE_ROD, rodUse);
						add(Items.ENDER_EYE, k);
					}
				}
			}
			default -> {
			}
		}
	}

	private void placeTableNow() {
		ServerLevel l = (ServerLevel) o.level();
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos p = o.blockPosition().relative(d);
			if (l.getBlockState(p).isAir() && l.getBlockState(p.below()).blocksMotion()) {
				l.setBlock(p, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
				o.skills().setMark(RunSkills.MARK_TABLE, p);
				return;
			}
		}
		add(Items.CRAFTING_TABLE, 1);
	}

	private void ensure(Item item, int total) {
		int have = o.getInventory().countItem(item);
		if (have < total) {
			add(item, total - have);
		}
	}

	private void add(Item item, int n) {
		int max = new ItemStack(item).getMaxStackSize();
		while (n > 0) {
			int k = Math.min(n, max);
			ItemStack left = o.getInventory().addItem(new ItemStack(item, k));
			if (!left.isEmpty() && o.level() instanceof ServerLevel sl) {
				o.spawnAtLocation(sl, left);
			}
			n -= k;
		}
	}

	// ---- 保存 ----

	void save(CompoundTag t) {
		t.putBoolean("FF", active);
		t.putInt("FFTicks", ticks);
		t.putString("FFStage", ticksStage == null ? "" : ticksStage.id);
		t.putString("FFWalkOnly", walkOnly == null ? "" : walkOnly.id);
	}

	void load(CompoundTag t) {
		active = t.getBooleanOr("FF", false);
		ticks = t.getIntOr("FFTicks", 0);
		ticksStage = Stage.byId(t.getStringOr("FFStage", ""));
		walkOnly = Stage.byId(t.getStringOr("FFWalkOnly", ""));
	}
}
