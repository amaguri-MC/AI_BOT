package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * 構造物の場所を ServerLevel / ChunkGenerator の検索で得て、そこへ歩き続ける。着いたら postEvent。
 * stronghold / village は ServerLevel#findNearestMapStructure(TagKey,...)、
 * fortress / bastion は ChunkGenerator#findNearestMapStructure(ServerLevel, HolderSet,...)。
 * 見つけた場所は SkillRunner の mark（キーは type 名）に覚え、Autopilot が使う。
 */
final class StructureSkill extends Skill {
	private final String type;
	private String label;
	private BlockPos found;
	private Vec3 goal;
	private int ticks;
	/** 検索半径（チャンク）。0 なら種類ごとの既定。近くだけ探したいとき（序盤の探索）に絞る */
	private final int radius;

	StructureSkill(FakeMember o, SkillRunner r, String type) {
		this(o, r, type, 0);
	}

	StructureSkill(FakeMember o, SkillRunner r, String type, int radiusChunks) {
		super(o, r);
		nav.setAllowDig(true); // 鉱石・要塞へ掘り進むスキルは掘ってよい
		this.type = type == null ? "" : type.toLowerCase().trim();
		this.radius = Math.max(0, radiusChunks);
	}

	private int rad(int def) {
		return radius > 0 ? radius : def;
	}

	@Override
	String begin() {
		ServerLevel l = SkillUtil.level(o);
		boolean nether = l.dimension().equals(Level.NETHER);
		BlockPos from = o.blockPosition();
		switch (type) {
			case "stronghold" -> {
				label = "要塞";
				if (!l.dimension().equals(Level.OVERWORLD)) {
					return "できない: 要塞はオーバーワールドにある";
				}
				// UNVERIFIED: 第3引数は検索半径（チャンク単位。/locate と同じ 100）、第4引数は既知を飛ばすか
				found = l.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, from, 100, false);
			}
			case "ruined_portal" -> {
				label = "廃ポータル";
				if (!l.dimension().equals(Level.OVERWORLD)) {
					return "できない: 廃ポータルはオーバーワールドで探す";
				}
				// 近く（8チャンク内）だけ。遠い廃ポータルに寄り道はしない
				found = l.findNearestMapStructure(StructureTags.RUINED_PORTAL, from, rad(8), false);
			}
			case "village" -> {
				label = "村";
				if (!l.dimension().equals(Level.OVERWORLD)) {
					return "できない: 村はオーバーワールドにある";
				}
				found = l.findNearestMapStructure(StructureTags.VILLAGE, from, rad(100), false);
			}
			case "shipwreck" -> {
				label = "沈没船";
				if (!l.dimension().equals(Level.OVERWORLD)) {
					return "できない: 沈没船はオーバーワールドにある";
				}
				found = l.findNearestMapStructure(StructureTags.SHIPWRECK, from, rad(8), false);
			}
			case "buried_treasure" -> {
				label = "埋もれた宝";
				if (!l.dimension().equals(Level.OVERWORLD)) {
					return "できない: 埋もれた宝はオーバーワールドにある";
				}
				// 宝の地図が指す先（ON_TREASURE_MAPS＝埋もれた宝）。地図はふつう近くを指す
				found = l.findNearestMapStructure(StructureTags.ON_TREASURE_MAPS, from, rad(50), false);
			}
			case "fortress" -> {
				label = "ネザー要塞";
				if (!nether) {
					return "できない: ネザー要塞はネザーにある（今はネザーにいない）";
				}
				found = byKey(l, from, BuiltinStructures.FORTRESS);
			}
			case "bastion" -> {
				label = "砦";
				if (!nether) {
					return "できない: 砦はネザーにある（今はネザーにいない）";
				}
				found = byKey(l, from, BuiltinStructures.BASTION_REMNANT);
			}
			default -> {
				return "できない: 知らない構造物 " + type;
			}
		}
		if (found == null) {
			return "できない: " + label + "が見つからない";
		}
		runner.setMark(type, found);
		goal = SkillUtil.snap(o, found.getX() + 0.5, found.getZ() + 0.5, o.getY());
		nav.go(goal, 10.0, 1.0);
		nav.setVertTol(256);
		nav.setSprint(true);
		return null;
	}

	private BlockPos byKey(ServerLevel l, BlockPos from, ResourceKey<Structure> key) {
		Holder<Structure> h = l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
		HolderSet<Structure> set = HolderSet.direct(h);
		Pair<BlockPos, Holder<Structure>> p = l.getChunkSource().getGenerator().findNearestMapStructure(l, set, from, 100, false);
		return p == null ? null : p.getFirst();
	}

	@Override
	void tick() {
		ticks++;
		nav.setSprint(true);
		// 近づくにつれて（読み込まれた地面に合わせて）目的地の高さを更新する
		if (ticks % 100 == 0 && nav.distance() < 40) {
			goal = SkillUtil.snap(o, found.getX() + 0.5, found.getZ() + 0.5, o.getY());
			nav.go(goal, 10.0, 1.0);
		}
		nav.tick();
		if (nav.arrived()) {
			String where = found.getX() + " " + o.blockPosition().getY() + " " + found.getZ();
			String ev = switch (type) {
				case "shipwreck" -> "沈没船あった";
				case "village" -> "村あった";
				case "ruined_portal" -> "廃ポータルあった";
				case "buried_treasure" -> "宝の地図の場所に着いた";
				default -> label + "に着いた";
			};
			finish(label + "に着いた " + where, ev + " 座標 " + where);
		} else if (nav.failed() || ticks > 20 * 60 * 40) {
			finish(label + "にたどり着けなかった", label + "にたどり着けなかった 座標 " + found.getX() + " " + found.getZ());
		}
	}

	@Override
	String status() {
		return label + "へ移動中（座標 " + found.getX() + " " + found.getZ() + "、あと" + Math.round(nav.distance()) + "m）";
	}
}
