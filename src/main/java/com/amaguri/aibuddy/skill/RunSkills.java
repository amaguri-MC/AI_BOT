package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import com.mojang.datafixers.util.Pair;

/**
 * 自動進行（Autopilot）用のスキルと、早送り用の一気にやる処理。
 * SkillRunner が SurvivalSkills → MotionSkills の次に問い合わせる。知らない名前は null。
 * 脳には渡さない（Tools は follow_player / stop_follow / resume_run だけ）。
 */
public final class RunSkills {
	public static final String MARK_PORTAL_OW = "portal_ow";
	public static final String MARK_PORTAL_NETHER = "portal_nether";
	public static final String MARK_FORTRESS = "fortress";
	public static final String MARK_STRONGHOLD = "stronghold";
	public static final String MARK_FRAME = "frame";
	public static final String MARK_RUIN = "ruined_portal";
	public static final String MARK_TABLE = "table";
	public static final String MARK_TREASURE = "buried_treasure";

	private RunSkills() {
	}

	// ---- 穴を掘ってよいか（段階ごとの方針。Autopilot が毎tick決める） ----

	/**
	 * トンネル・下掘りを許すか。唯一の源は SkillRunner.allowDig（既定 false）。Autopilot はネザー要塞・マグマだまりを
	 * 探す段階だけ true にする。MineSkill の対象選び（立って届く物だけにするか）と Nav が読む。
	 */
	public static void setDigAllowed(FakeMember o, boolean on) {
		o.skills().setAllowDig(on);
	}

	/** 掘ってよいか（SkillRunner.allowDig を読むだけ）。 */
	public static boolean digAllowed(FakeMember o) {
		return o.skills().allowDig();
	}

	/** 柱・橋に使えるブロックの数（Plan の行の条件をスキル側の数え方と揃える）。 */
	public static int scaffoldCount(FakeMember o) {
		return Motion.scaffoldCount(o);
	}

	/**
	 * 立った位置から届くか: 空気に面していて、近く（横3・下4〜上1）に足場（下が固く、足と頭が空き）があり、
	 * そこから目の高さで4.5ブロック以内。掘り進まないと届かない物は false。
	 */
	public static boolean reachableFromStand(ServerLevel l, BlockPos p) {
		boolean air = false;
		for (Direction d : Direction.values()) {
			if (l.getBlockState(p.relative(d)).isAir()) {
				air = true;
				break;
			}
		}
		if (!air) {
			return false;
		}
		Vec3 c = Vec3.atCenterOf(p);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -4; dy <= 1; dy++) {
					m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
					if (m.equals(p) || !l.hasChunkAt(m)) {
						continue;
					}
					BlockState below = l.getBlockState(m.below());
					BlockState feet = l.getBlockState(m);
					BlockState head = l.getBlockState(m.above());
					if (!below.blocksMotion() || feet.blocksMotion() || head.blocksMotion()
							|| !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
						continue;
					}
					if (new Vec3(m.getX() + 0.5, m.getY() + 1.62, m.getZ() + 0.5).distanceToSqr(c) <= 4.5 * 4.5) {
						return true;
					}
				}
			}
		}
		return false;
	}

	static Skill create(String name, FakeMember o, SkillRunner r, JsonObject in) {
		if (name == null) {
			return null;
		}
		return switch (name) {
			case "collect_items" -> new CollectSkill(o, r);
			case "emote" -> new EmoteSkill(o, r, SkillRunner.str(in, "kind"));
			case "find_structure" -> new StructureSkill(o, r, SkillRunner.str(in, "type"), intOr(in, "radius", 0));
			case "build_nether_portal" -> new PortalSkill(o, r);
			case "fill_water" -> new FillWater(o, r);
			case "enter_portal" -> new EnterPortal(o, r, optPos(in));
			case "loot_chests" -> new LootChests(o, r, intOr(in, "radius", 16));
			case "place_table" -> new PlaceTable(o, r);
			case "dig_treasure" -> new DigTreasure(o, r);
			case "locate_stronghold" -> new LocateStronghold(o, r);
			case "fill_end_portal" -> new FillEndPortal(o, r);
			default -> null;
		};
	}

	/** 脳には渡さないので空。 */
	static void specs(JsonArray out) {
	}

	private static int intOr(JsonObject in, String k, int def) {
		return in.has(k) && !in.get(k).isJsonNull() ? (int) in.get(k).getAsDouble() : def;
	}

	private static BlockPos optPos(JsonObject in) {
		if (in.has("x") && in.has("y") && in.has("z")) {
			return BlockPos.containing(SkillRunner.num(in, "x"), SkillRunner.num(in, "y"), SkillRunner.num(in, "z"));
		}
		return null;
	}

	static ItemStack stackOf(FakeMember o, Item item) {
		var c = o.getInventory();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack st = c.getItem(i);
			if (!st.isEmpty() && st.is(item)) {
				return st;
			}
		}
		return ItemStack.EMPTY;
	}

	static BlockPos nearestBlock(ServerLevel l, BlockPos c, Block b, int r, int dyDown, int dyUp) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (!l.hasChunkAt(m.set(c.getX() + dx, c.getY(), c.getZ() + dz))) {
					continue;
				}
				for (int dy = -dyDown; dy <= dyUp; dy++) {
					m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					if (l.getBlockState(m).is(b)) {
						double d = m.distSqr(c);
						if (d < bd) {
							bd = d;
							best = m.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	// =====================================================================
	// 早送り・自動進行から使う公開ヘルパー
	// =====================================================================

	/** オーバーワールドの一番近い要塞（エンダーアイが指す先）。 */
	public static BlockPos findStronghold(ServerLevel ow, BlockPos from) {
		// UNVERIFIED: 検索半径はチャンク単位（/locate と同じ 100）
		return ow.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, from, 100, false);
	}

	/** ネザーの一番近いネザー要塞。 */
	public static BlockPos findFortress(ServerLevel nether, BlockPos from) {
		Holder<Structure> h = nether.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS);
		Pair<BlockPos, Holder<Structure>> p = nether.getChunkSource().getGenerator()
				.findNearestMapStructure(nether, HolderSet.direct(h), from, 100, false);
		return p == null ? null : p.getFirst();
	}

	/** 要塞のエンドポータルの部屋。frames は額縁12個、center は3x3の真ん中、stand は部屋の中で立てる所。 */
	public record PortalRoom(BlockPos center, BlockPos stand, List<BlockPos> frames) {
	}

	/** 要塞の構造データから PortalRoom を探し、中の額縁を数える。見つからなければ null。チャンクを読み込むので重い。 */
	public static PortalRoom portalRoom(ServerLevel ow, BlockPos stronghold) {
		Structure sh = ow.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.STRONGHOLD).value();
		// UNVERIFIED: startsForStructure は参照を持つチャンクの STRUCTURE_STARTS を読む（未生成なら生成される）
		List<StructureStart> starts = ow.structureManager().startsForStructure(ChunkPos.containing(stronghold), s -> s == sh);
		BoundingBox room = null;
		for (StructureStart st : starts) {
			for (StructurePiece pc : st.getPieces()) {
				if (pc instanceof StrongholdPieces.PortalRoom) {
					room = pc.getBoundingBox();
				}
			}
		}
		if (room == null) {
			return null;
		}
		List<BlockPos> frames = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ())) {
			if (ow.getBlockState(p).is(Blocks.END_PORTAL_FRAME)) {
				frames.add(p.immutable());
			}
		}
		if (frames.isEmpty()) {
			return null;
		}
		long sx = 0;
		long sz = 0;
		for (BlockPos p : frames) {
			sx += p.getX();
			sz += p.getZ();
		}
		BlockPos center = new BlockPos((int) Math.floorDiv(sx, frames.size()), frames.get(0).getY(),
				(int) Math.floorDiv(sz, frames.size()));
		BlockPos stand = null;
		double bd = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ())) {
			double d = p.distSqr(center);
			if (d < 9 || d > 64 || d >= bd) {
				continue;
			}
			if (standable(ow, p)) {
				bd = d;
				stand = p.immutable();
			}
		}
		return new PortalRoom(center, stand == null ? center.above() : stand, frames);
	}

	static boolean standable(ServerLevel l, BlockPos feet) {
		BlockState below = l.getBlockState(feet.below());
		return below.blocksMotion() && below.getFluidState().isEmpty() && !below.is(Blocks.END_PORTAL_FRAME)
				&& l.getBlockState(feet).isAir() && l.getBlockState(feet.above()).isAir() && !SkillUtil.nearLava(l, feet)
				&& !SkillUtil.nearLava(l, feet.below());
	}

	/** near の周り半径 r で立てる場所（足元が固く、2マス空き、溶岩なし）。無ければ足場を作って返す。 */
	public static BlockPos safeSpot(ServerLevel l, BlockPos near, int r) {
		boolean ceiling = l.dimensionType().hasCeiling();
		for (int rr = 0; rr <= r; rr++) {
			for (int dx = -rr; dx <= rr; dx++) {
				for (int dz = -rr; dz <= rr; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != rr) {
						continue;
					}
					int x = near.getX() + dx;
					int z = near.getZ() + dz;
					if (!ceiling) {
						int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
						BlockPos p = new BlockPos(x, y, z);
						if (standable(l, p)) {
							return p;
						}
						continue;
					}
					for (int dy = 0; dy <= 24; dy++) {
						for (int sgn : new int[] { 1, -1 }) {
							BlockPos p = new BlockPos(x, near.getY() + dy * sgn, z);
							if (p.getY() > l.getMinY() + 2 && p.getY() < 120 && standable(l, p)) {
								return p;
							}
						}
					}
				}
			}
		}
		// 見つからなければ足場を作る（ネザーの溶岩の海の上など）
		BlockPos p = new BlockPos(near.getX(), Math.max(near.getY(), l.getMinY() + 5), near.getZ());
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				l.setBlock(p.offset(dx, -1, dz), (ceiling ? Blocks.NETHERRACK : Blocks.COBBLESTONE).defaultBlockState(), 3);
				l.setBlock(p.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
				l.setBlock(p.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
			}
		}
		return p;
	}

	/** 早送り用: 目の前に黒曜石の枠を組んで着火し、場所を覚える。内側の左下を返す。 */
	public static BlockPos instantNetherPortal(FakeMember o) {
		ServerLevel l = SkillUtil.level(o);
		BlockPos p = PortalSkill.buildInstant(l, o.blockPosition(), Direction.fromYRot(o.getYRot()));
		o.skills().setMark(SkillUtil.isNether(o) ? MARK_PORTAL_NETHER : MARK_PORTAL_OW, p);
		return p;
	}

	/** 額縁に持っているアイをはめる（1個ずつ）。全部はまったらポータルを開く。はめた数を返す。 */
	public static int fillFramesInstant(FakeMember o, List<BlockPos> frames) {
		ServerLevel l = SkillUtil.level(o);
		int n = 0;
		for (BlockPos f : frames) {
			BlockState s = l.getBlockState(f);
			if (!s.is(Blocks.END_PORTAL_FRAME) || s.getValue(EndPortalFrameBlock.HAS_EYE)) {
				continue;
			}
			if (o.getInventory().countItem(Items.ENDER_EYE) <= 0) {
				break;
			}
			o.getInventory().removeItemType(Items.ENDER_EYE, 1);
			l.setBlock(f, s.setValue(EndPortalFrameBlock.HAS_EYE, true), 2);
			l.levelEvent(LevelEvent.END_PORTAL_FRAME_FILL, f, 0);
			n++;
		}
		if (!frames.isEmpty()) {
			openEndPortal(l, frames.get(0));
		}
		return n;
	}

	/** EnderEyeItem.useOn と同じ: 額縁の形がそろっていれば 3x3 をエンドポータルにする。開いたら真ん中を返す。 */
	static BlockPos openEndPortal(ServerLevel l, BlockPos anyFrame) {
		BlockPattern.BlockPatternMatch m = EndPortalFrameBlock.getOrCreatePortalShape().find(l, anyFrame);
		if (m == null) {
			return null;
		}
		BlockPos origin = m.getFrontTopLeft().offset(-3, 0, -3);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				l.setBlock(origin.offset(i, 0, j), Blocks.END_PORTAL.defaultBlockState(), 2);
			}
		}
		l.globalLevelEvent(LevelEvent.SOUND_END_PORTAL_SPAWN, origin.offset(1, 0, 1), 0);
		return origin.offset(1, 0, 1);
	}

	/** 今いる場所のゲート（ネザーゲート/エンドポータル）を、バニラの行き先計算でくぐらせる。新しい個体を返す。 */
	public static net.minecraft.world.entity.Entity usePortalNow(FakeMember o, BlockPos portalPos) {
		ServerLevel l = SkillUtil.level(o);
		Block b = l.getBlockState(portalPos).getBlock();
		if (!(b instanceof Portal portal)) {
			return null;
		}
		// UNVERIFIED: Portal#getPortalDestination をプレイヤー以外で直接呼んでもバニラと同じ行き先（ゲートの生成・連結込み）になる前提
		TeleportTransition t = portal.getPortalDestination(l, o, portalPos);
		if (t == null) {
			return null;
		}
		return o.teleport(t);
	}

	// =====================================================================
	// スキル
	// =====================================================================

	/** 持っている作業台を足元の横に置いて覚える（作業台はここで使う）。 */
	static final class PlaceTable extends Skill {
		PlaceTable(FakeMember o, SkillRunner r) {
			super(o, r);
		}

		@Override
		String begin() {
			if (Inv.count(o, Items.CRAFTING_TABLE) <= 0) {
				return "できない: 材料不足 作業台×1";
			}
			return null;
		}

		@Override
		void tick() {
			ServerLevel l = SkillUtil.level(o);
			BlockPos me = o.blockPosition();
			Direction face = Direction.fromYRot(o.getYRot());
			List<Direction> order = new ArrayList<>(List.of(face, face.getClockWise(), face.getCounterClockWise(), face.getOpposite()));
			for (Direction d : order) {
				BlockPos p = me.relative(d);
				if (l.getBlockState(p).canBeReplaced() && l.getBlockState(p).getFluidState().isEmpty()
						&& l.getBlockState(p.below()).blocksMotion()) {
					Inv.equip(o, stackOf(o, Items.CRAFTING_TABLE));
					SkillUtil.lookAt(o, Vec3.atCenterOf(p));
					SkillUtil.swing(o);
					Inv.remove(o, Items.CRAFTING_TABLE, 1);
					BlockState t = Blocks.CRAFTING_TABLE.defaultBlockState();
					l.setBlock(p, t, 3);
					l.playSound(null, p, t.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.8f);
					runner.setMark(MARK_TABLE, p);
					finish("作業台を置いた " + SkillUtil.pos(p), null);
					return;
				}
			}
			finish("できない: 作業台を置く場所が無い", null);
		}

		@Override
		String status() {
			return "作業台を置いている";
		}
	}

	/** 宝の地図の場所（StructureSkill で着いた所）で、埋まったチェストの真上を掘って開ける。 */
	static final class DigTreasure extends Skill {
		private BlockPos chest;
		private int ticks;
		private int y;

		DigTreasure(FakeMember o, SkillRunner r) {
			super(o, r);
		}

		@Override
		String begin() {
			ServerLevel l = SkillUtil.level(o);
			BlockPos m = runner.mark(MARK_TREASURE);
			BlockPos c = m == null ? o.blockPosition() : new BlockPos(m.getX(), o.blockPosition().getY(), m.getZ());
			chest = nearestBlock(l, c, Blocks.CHEST, 10, 16, 4);
			if (chest == null) {
				return "できない: 宝のチェストが見つからない";
			}
			y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chest.getX(), chest.getZ()) - 1;
			return null;
		}

		@Override
		void tick() {
			ticks++;
			ServerLevel l = SkillUtil.level(o);
			if (ticks > 20 * 80) {
				finish("できない: 宝まで掘れない", null);
				return;
			}
			if (digger.active()) {
				if (digger.tick() == Digger.PROGRESS) {
					return;
				}
			}
			// 上から順に、チェストの真上の柱を掘る
			while (y > chest.getY() && (l.getBlockState(new BlockPos(chest.getX(), y, chest.getZ())).isAir()
					|| !l.getBlockState(new BlockPos(chest.getX(), y, chest.getZ())).getFluidState().isEmpty())) {
				y--;
			}
			Vec3 target = Vec3.atCenterOf(y > chest.getY() ? new BlockPos(chest.getX(), y, chest.getZ()) : chest);
			if (o.getEyePosition().distanceToSqr(target) > 4.3 * 4.3) {
				nav.go(Vec3.atBottomCenterOf(new BlockPos(chest.getX(), Math.max(y, chest.getY()) + 1, chest.getZ())), 1.5, 1.0);
				nav.setVertTol(6.0);
				nav.tick();
				return;
			}
			nav.stop();
			if (y > chest.getY()) {
				if (!digger.begin(new BlockPos(chest.getX(), y, chest.getZ()))) {
					y--;
				}
				return;
			}
			BlockEntity be = l.getBlockEntity(chest);
			int got = 0;
			if (be instanceof RandomizableContainer rc) {
				SkillUtil.lookAt(o, target);
				SkillUtil.swing(o);
				rc.unpackLootTable(null); // UNVERIFIED: プレイヤー無しで中身を生成（LootChests と同じ）
				for (int i = 0; i < rc.getContainerSize(); i++) {
					ItemStack st = rc.removeItemNoUpdate(i);
					if (!st.isEmpty()) {
						got += st.getCount();
						Inv.add(o, st);
					}
				}
				rc.setChanged();
				l.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 1.0f);
			}
			finish("埋もれた宝を開けた（" + got + "個）", "埋もれた宝を掘り当てた（" + got + "個）");
		}

		@Override
		String status() {
			return "埋もれた宝を掘っている";
		}
	}

	/** 空のバケツで近くの水を汲む。 */
	static final class FillWater extends Skill {
		private BlockPos water;
		private int ticks;

		FillWater(FakeMember o, SkillRunner r) {
			super(o, r);
		}

		@Override
		String begin() {
			if (Inv.count(o, Items.BUCKET) <= 0) {
				return "できない: 材料不足 バケツ×1";
			}
			if (SkillUtil.isNether(o)) {
				return "できない: ネザーでは水を汲めない";
			}
			water = findWater();
			if (water == null) {
				return "できない: 近くに水が見つからない";
			}
			nav.go(Vec3.atBottomCenterOf(water.above()), 3.0, 1.0);
			nav.setSprint(true);
			return null;
		}

		private BlockPos findWater() {
			ServerLevel l = SkillUtil.level(o);
			BlockPos c = o.blockPosition();
			BlockPos best = null;
			double bd = Double.MAX_VALUE;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int dx = -32; dx <= 32; dx++) {
				for (int dz = -32; dz <= 32; dz++) {
					if (!l.hasChunkAt(m.set(c.getX() + dx, c.getY(), c.getZ() + dz))) {
						continue;
					}
					for (int dy = -8; dy <= 6; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						BlockState s = l.getBlockState(m);
						if (s.is(Blocks.WATER) && s.getFluidState().isSource() && !l.getBlockState(m.above()).blocksMotion()) {
							double d = m.distSqr(c);
							if (d < bd) {
								bd = d;
								best = m.immutable();
							}
						}
					}
				}
			}
			return best;
		}

		@Override
		void tick() {
			ticks++;
			ServerLevel l = SkillUtil.level(o);
			Vec3 c = Vec3.atCenterOf(water);
			if (o.getEyePosition().distanceToSqr(c) > 4.3 * 4.3) {
				nav.tick();
				if (nav.failed() || ticks > 20 * 40) {
					finish("できない: 水にたどり着けない", null);
				}
				return;
			}
			nav.stop();
			SkillUtil.lookAt(o, c);
			Inv.equip(o, stackOf(o, Items.BUCKET));
			SkillUtil.swing(o);
			BlockState s = l.getBlockState(water);
			if (!s.is(Blocks.WATER)) {
				water = findWater();
				if (water == null) {
					finish("できない: 水が無くなった", null);
				}
				return;
			}
			// UNVERIFIED: 海・川の源は周りから戻るので消さなくても同じ。小さな水たまりは消える（バニラと同じく源を取る）
			l.setBlock(water, Blocks.AIR.defaultBlockState(), 3);
			l.playSound(null, water, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1.0f, 1.0f);
			Inv.remove(o, Items.BUCKET, 1);
			Inv.add(o, new ItemStack(Items.WATER_BUCKET));
			finish("水を汲んだ", null);
		}

		@Override
		String status() {
			return "バケツに水を汲みに行っている";
		}
	}

	/** ゲートに入る。入るとバニラの処理で次元を移る（この個体は消えて新しい個体になる）。 */
	static final class EnterPortal extends Skill {
		private BlockPos portal;
		private int ticks;
		private int inside;

		EnterPortal(FakeMember o, SkillRunner r, BlockPos given) {
			super(o, r);
			this.portal = given;
		}

		@Override
		String begin() {
			ServerLevel l = SkillUtil.level(o);
			if (portal == null || !isPortal(l.getBlockState(portal))) {
				BlockPos near = nearestBlock(l, o.blockPosition(), Blocks.NETHER_PORTAL, 24, 8, 8);
				if (near == null) {
					near = nearestBlock(l, o.blockPosition(), Blocks.END_PORTAL, 16, 8, 4);
				}
				if (near == null && portal != null && !l.hasChunkAt(portal)) {
					// 遠くて読み込まれていない: まず近くまで歩く
					near = portal;
				} else if (near == null) {
					return "できない: 近くにゲートが無い";
				}
				portal = near;
			}
			// ゲートの一番下のマスへ
			while (isPortal(l.getBlockState(portal.below()))) {
				portal = portal.below();
			}
			nav.go(Vec3.atBottomCenterOf(portal), 0.4, 1.0);
			nav.setVertTol(1.0);
			nav.setSprint(true);
			return null;
		}

		private static boolean isPortal(BlockState s) {
			return s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL);
		}

		@Override
		void tick() {
			ticks++;
			ServerLevel l = SkillUtil.level(o);
			if (!isPortal(l.getBlockState(portal)) && l.hasChunkAt(portal)) {
				finish("できない: ゲートが消えている", null);
				return;
			}
			boolean in = isPortal(l.getBlockState(o.blockPosition())) || isPortal(l.getBlockState(o.blockPosition().below()));
			if (in) {
				nav.stop();
				inside++;
				// プレイヤー以外は待ち時間なしでくぐる。数秒たっても移らなければ直接くぐらせる
				if (inside == 60) {
					usePortalNow(o, isPortal(l.getBlockState(o.blockPosition())) ? o.blockPosition() : o.blockPosition().below());
				}
				if (inside > 200) {
					finish("できない: ゲートをくぐれなかった", null);
				}
				return;
			}
			nav.tick();
			double h = SkillUtil.horiz(o.position(), Vec3.atBottomCenterOf(portal));
			if (h < 1.6 && ticks % 20 == 0) {
				// 最後の一歩（穴に飛び込む・枠の中へ入る）
				o.setPos(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
			}
			if (ticks > 20 * 90 || nav.failed() && h > 3) {
				finish("できない: ゲートにたどり着けない", null);
			}
		}

		@Override
		String status() {
			return "ゲートに入ろうとしている（" + SkillUtil.pos(portal) + "）";
		}
	}

	/** 近くのチェスト・樽（構造物の戦利品入り）を開けて中身をもらう。radius は探す半径（村なら広く）。 */
	static final class LootChests extends Skill {
		private final List<BlockPos> chests = new ArrayList<>();
		private final int radius;
		private int ticks;
		private int got;

		LootChests(FakeMember o, SkillRunner r, int radius) {
			super(o, r);
			this.radius = Math.max(4, Math.min(48, radius));
		}

		@Override
		String begin() {
			ServerLevel l = SkillUtil.level(o);
			BlockPos c = o.blockPosition();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			int dyR = radius > 16 ? 12 : 8;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (!l.hasChunkAt(m.set(c.getX() + dx, c.getY(), c.getZ() + dz))) {
						continue;
					}
					for (int dy = -dyR; dy <= dyR; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						BlockState bs = l.getBlockState(m);
						if ((bs.is(Blocks.CHEST) || bs.is(Blocks.BARREL)) && l.getBlockEntity(m) instanceof RandomizableContainer) {
							chests.add(m.immutable());
						}
					}
				}
			}
			if (chests.isEmpty()) {
				return "できない: 近くにチェストが無い";
			}
			chests.sort((a, b) -> Double.compare(a.distSqr(c), b.distSqr(c)));
			return null;
		}

		@Override
		void tick() {
			ticks++;
			if (chests.isEmpty()) {
				finish("チェストから" + got + "個もらった", got > 0 ? "チェストを開けて" + got + "個もらった" : null);
				return;
			}
			ServerLevel l = SkillUtil.level(o);
			BlockPos p = chests.get(0);
			Vec3 c = Vec3.atCenterOf(p);
			if (o.getEyePosition().distanceToSqr(c) > 4.0 * 4.0) {
				nav.go(Vec3.atBottomCenterOf(p), 1.8, 1.0);
				nav.tick();
				if (nav.failed() || ticks > 20 * 30) {
					chests.remove(0);
					ticks = 0;
				}
				return;
			}
			nav.stop();
			SkillUtil.lookAt(o, c);
			SkillUtil.swing(o);
			BlockEntity be = l.getBlockEntity(p);
			if (be instanceof RandomizableContainer rc) {
				// UNVERIFIED: unpackLootTable(null) でプレイヤー無しでも中身が生成される（バニラはドロッパー等で null を渡す）
				rc.unpackLootTable(null);
				for (int i = 0; i < rc.getContainerSize(); i++) {
					ItemStack st = rc.removeItemNoUpdate(i);
					if (!st.isEmpty()) {
						got += st.getCount();
						Inv.add(o, st);
					}
				}
				rc.setChanged();
				l.playSound(null, p, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 1.0f);
			}
			chests.remove(0);
		}

		@Override
		String status() {
			return "チェストを開けに行っている（残り" + chests.size() + "）";
		}
	}

	/**
	 * エンダーアイを実際に投げて（EyeOfEnder を飛ばす）方向を見て、その方向へ走る。近づいたら要塞のエンドポータルの部屋まで掘って行く。
	 * アイは2回まで投げる（割れると額縁用が足りなくなるので、持ち数が少なければ投げずに進む）。
	 */
	static final class LocateStronghold extends Skill {
		private enum St { THROW, WATCH, PICKUP, TRAVEL, ROOM }

		private St st = St.THROW;
		private BlockPos target;
		private EyeOfEnder eye;
		private Vec3 eyeLast;
		private int throwsDone;
		private int ticks;
		private int stTicks;
		private PortalRoom room;

		LocateStronghold(FakeMember o, SkillRunner r) {
			super(o, r);
		}

		@Override
		String begin() {
			ServerLevel l = SkillUtil.level(o);
			if (!l.dimension().equals(Level.OVERWORLD)) {
				return "できない: 要塞はオーバーワールドで探す";
			}
			target = runner.mark(MARK_STRONGHOLD);
			if (target == null) {
				target = findStronghold(l, o.blockPosition());
			}
			if (target == null) {
				return "できない: 要塞が見つからない";
			}
			runner.setMark(MARK_STRONGHOLD, target);
			if (Inv.count(o, Items.ENDER_EYE) <= 10 || horiz() < 48) {
				st = horiz() < 48 ? St.ROOM : St.TRAVEL;
				startTravel();
			}
			return null;
		}

		private double horiz() {
			return SkillUtil.horiz(o.position(), Vec3.atCenterOf(target));
		}

		private void set(St s) {
			st = s;
			stTicks = 0;
		}

		private void startTravel() {
			double d = horiz();
			double leg = d > 500 && throwsDone < 2 && Inv.count(o, Items.ENDER_EYE) > 10 ? d * 0.6 : d;
			Vec3 dir = Vec3.atCenterOf(target).subtract(o.position()).multiply(1, 0, 1).normalize();
			Vec3 g = o.position().add(dir.scale(Math.max(0, leg - 20)));
			g = SkillUtil.snap(o, g.x, g.z, o.getY());
			nav.go(g, 6.0, 1.0);
			nav.setVertTol(64);
			nav.setSprint(true);
		}

		@Override
		void tick() {
			ticks++;
			stTicks++;
			ServerLevel l = SkillUtil.level(o);
			switch (st) {
				case THROW -> {
					ItemStack es = stackOf(o, Items.ENDER_EYE);
					if (es.isEmpty()) {
						set(St.TRAVEL);
						startTravel();
						return;
					}
					Inv.equip(o, es);
					Vec3 eyePos = o.getEyePosition();
					SkillUtil.lookAt(o, eyePos.add(Vec3.atCenterOf(target).subtract(eyePos).normalize().scale(8)).add(0, 4, 0));
					SkillUtil.swing(o);
					eye = new EyeOfEnder(l, o.getX(), o.getY(0.5), o.getZ());
					eye.setItem(new ItemStack(Items.ENDER_EYE));
					// UNVERIFIED: 26.2 の signalTo(Vec3)。中で surviveAfterDeath（8割で落ちる）を決める
					eye.signalTo(Vec3.atCenterOf(target));
					l.addFreshEntity(eye);
					l.playSound(null, o.getX(), o.getY(), o.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 0.5f,
							0.4f / (o.getRandom().nextFloat() * 0.4f + 0.8f));
					Inv.remove(o, Items.ENDER_EYE, 1);
					throwsDone++;
					nav.stop();
					set(St.WATCH);
				}
				case WATCH -> {
					if (eye != null && !eye.isRemoved()) {
						eyeLast = eye.position();
						SkillUtil.lookAt(o, eyeLast);
						if (stTicks < 120) {
							return;
						}
					}
					eye = null;
					set(St.PICKUP);
				}
				case PICKUP -> {
					ItemEntity drop = null;
					if (eyeLast != null) {
						for (ItemEntity ie : SkillUtil.items(o, 24)) {
							if (ie.getItem().is(Items.ENDER_EYE) && ie.position().distanceToSqr(eyeLast) < 25) {
								drop = ie;
								break;
							}
						}
					}
					if (drop == null || stTicks > 160) {
						set(St.TRAVEL);
						startTravel();
						return;
					}
					nav.go(drop.position(), 0.8, 1.0);
					nav.tick();
				}
				case TRAVEL -> {
					nav.setSprint(true);
					nav.tick();
					if (horiz() < 48) {
						set(St.ROOM);
						return;
					}
					if (nav.arrived() || nav.failed() || stTicks > 20 * 120) {
						if (throwsDone < 2 && Inv.count(o, Items.ENDER_EYE) > 10) {
							set(St.THROW);
						} else {
							startTravel();
							stTicks = 0;
						}
					}
				}
				case ROOM -> {
					if (room == null) {
						room = portalRoom(l, target);
						if (room == null) {
							finish("できない: 要塞のエンドポータルの部屋が見つからない", null);
							return;
						}
						runner.setMark(MARK_FRAME, room.center());
						nav.go(Vec3.atBottomCenterOf(room.stand()), 2.0, 1.0);
						nav.setVertTol(2.0);
					}
					nav.tick();
					if (o.blockPosition().distSqr(room.center()) < 7 * 7) {
						String where = SkillUtil.pos(room.center());
						finish("要塞のエンドポータルに着いた " + where, "要塞のエンドポータルに着いた 座標 " + where);
					} else if (nav.failed() || stTicks > 20 * 240) {
						finish("できない: エンドポータルの部屋にたどり着けない", null);
					}
				}
				default -> {
				}
			}
		}

		@Override
		String status() {
			return switch (st) {
				case THROW, WATCH -> "エンダーアイを投げて方向を見ている";
				case PICKUP -> "落ちたエンダーアイを拾っている";
				case TRAVEL -> "要塞へ走っている（あと" + Math.round(horiz()) + "m）";
				default -> "要塞のエンドポータルの部屋へ掘り進んでいる";
			};
		}
	}

	/** 額縁にエンダーアイを1個ずつはめてエンドポータルを開き、飛び込む。 */
	static final class FillEndPortal extends Skill {
		private final List<BlockPos> todo = new ArrayList<>();
		private BlockPos anyFrame;
		private BlockPos center;
		private int ticks;
		private int delay;
		private int filled;

		FillEndPortal(FakeMember o, SkillRunner r) {
			super(o, r);
		}

		@Override
		String begin() {
			ServerLevel l = SkillUtil.level(o);
			BlockPos c = runner.mark(MARK_FRAME);
			if (c == null || c.distSqr(o.blockPosition()) > 24 * 24) {
				c = o.blockPosition();
			}
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int dx = -10; dx <= 10; dx++) {
				for (int dz = -10; dz <= 10; dz++) {
					for (int dy = -6; dy <= 6; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						BlockState s = l.getBlockState(m);
						if (s.is(Blocks.END_PORTAL_FRAME)) {
							anyFrame = m.immutable();
							if (!s.getValue(EndPortalFrameBlock.HAS_EYE)) {
								todo.add(m.immutable());
							}
						} else if (s.is(Blocks.END_PORTAL) && center == null) {
							center = m.immutable();
						}
					}
				}
			}
			if (anyFrame == null) {
				return "できない: 近くにエンドポータルの額縁が無い";
			}
			int eyes = Inv.count(o, Items.ENDER_EYE);
			if (center == null && eyes < todo.size()) {
				return "できない: エンダーアイが足りない あと" + (todo.size() - eyes) + "個";
			}
			BlockPos me = o.blockPosition();
			todo.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
			return null;
		}

		@Override
		void tick() {
			ticks++;
			if (delay > 0) {
				delay--;
				return;
			}
			ServerLevel l = SkillUtil.level(o);
			if (!todo.isEmpty()) {
				BlockPos f = todo.get(0);
				Vec3 c = Vec3.atCenterOf(f);
				if (o.getEyePosition().distanceToSqr(c) > 4.3 * 4.3) {
					nav.go(Vec3.atBottomCenterOf(f.above()), 2.5, 1.0);
					nav.tick();
					if (ticks > 20 * 60) {
						finish("できない: 額縁に近づけない", null);
					}
					return;
				}
				nav.stop();
				BlockState s = l.getBlockState(f);
				todo.remove(0);
				if (!s.is(Blocks.END_PORTAL_FRAME) || s.getValue(EndPortalFrameBlock.HAS_EYE)) {
					return;
				}
				ItemStack es = stackOf(o, Items.ENDER_EYE);
				if (es.isEmpty()) {
					finish("できない: エンダーアイが足りない あと" + (todo.size() + 1) + "個", null);
					return;
				}
				Inv.equip(o, es);
				SkillUtil.lookAt(o, c);
				SkillUtil.swing(o);
				Inv.remove(o, Items.ENDER_EYE, 1);
				l.setBlock(f, s.setValue(EndPortalFrameBlock.HAS_EYE, true), 2);
				l.levelEvent(LevelEvent.END_PORTAL_FRAME_FILL, f, 0);
				filled++;
				delay = 6;
				return;
			}
			if (center == null) {
				center = openEndPortal(l, anyFrame);
				if (center == null) {
					finish("できない: 額縁の形がそろわない", null);
					return;
				}
				o.postEvent("エンドポータルを開けた 座標 " + SkillUtil.pos(center));
				delay = 20;
				return;
			}
			// 飛び込む
			if (!l.getBlockState(center).is(Blocks.END_PORTAL)) {
				finish("できない: エンドポータルが消えた", null);
				return;
			}
			double h = SkillUtil.horiz(o.position(), Vec3.atBottomCenterOf(center));
			if (h > 2.5) {
				nav.go(Vec3.atBottomCenterOf(center), 2.0, 1.0);
				nav.setVertTol(3.0);
				nav.tick();
			} else {
				nav.stop();
				o.getJumpControl().jump();
				o.setPos(center.getX() + 0.5, center.getY() + 0.2, center.getZ() + 0.5);
			}
			if (ticks > 20 * 120) {
				finish("できない: エンドポータルに入れない", null);
			}
		}

		@Override
		String status() {
			return todo.isEmpty() ? "エンドポータルに飛び込もうとしている" : "額縁にエンダーアイをはめている（" + filled + "個）";
		}
	}
}
