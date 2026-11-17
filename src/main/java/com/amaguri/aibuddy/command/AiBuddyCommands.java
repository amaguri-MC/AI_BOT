package com.amaguri.aibuddy.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import com.amaguri.aibuddy.brain.BrainManager;
import com.amaguri.aibuddy.brain.Persona;
import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;
import com.amaguri.aibuddy.run.Stage;

/** /aibuddy spawn|remove|melt|freeze|reload|run （OP2）。 */
public final class AiBuddyCommands {
	private AiBuddyCommands() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aibuddy")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("spawn")
						.then(Commands.argument("personaId", StringArgumentType.word())
								.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "personaId")))))
				.then(Commands.literal("remove")
						.then(Commands.argument("target", StringArgumentType.word())
								.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "target")))))
				.then(Commands.literal("melt")
						.then(Commands.argument("value", FloatArgumentType.floatArg(0f, 1f))
								.executes(ctx -> melt(ctx, FloatArgumentType.getFloat(ctx, "value")))))
				.then(Commands.literal("freeze")
						.then(Commands.argument("enabled", BoolArgumentType.bool())
								.executes(ctx -> freeze(ctx, BoolArgumentType.getBool(ctx, "enabled")))))
				.then(Commands.literal("reload")
						.executes(AiBuddyCommands::reload))
				.then(Commands.literal("run")
						.then(Commands.literal("on").executes(ctx -> runEnable(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> runEnable(ctx, false)))
						.then(Commands.literal("status").executes(AiBuddyCommands::runStatus))
						.then(Commands.literal("skipto")
								.then(Commands.argument("stage", StringArgumentType.word())
										.suggests((ctx, b) -> {
											for (Stage st : Stage.values()) {
												b.suggest(st.id);
											}
											return b.buildFuture();
										})
										.executes(ctx -> runSkip(ctx, StringArgumentType.getString(ctx, "stage")))))));
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, String personaId) {
		CommandSourceStack source = ctx.getSource();
		Persona persona = BrainManager.persona(personaId);
		if (persona == null) {
			source.sendFailure(Component.translatableWithFallback("commands.aibuddy.spawn.unknown_persona",
					"ペルソナが見つからない: %s", personaId));
			return 0;
		}
		ServerLevel level = source.getLevel();
		FakeMember member = ModEntities.FAKE_MEMBER.create(level, EntitySpawnReason.COMMAND);
		if (member == null) {
			source.sendFailure(Component.translatableWithFallback("commands.aibuddy.spawn.failed",
					"スポーンできなかった"));
			return 0;
		}
		Vec3 pos = source.getPosition();
		member.snapTo(pos.x, pos.y, pos.z, source.getRotation().y, 0f);
		member.setPersonaId(persona.id());
		member.setSkinName(persona.skin());
		member.setDisplayNameText(persona.displayName());
		if (!level.addFreshEntity(member)) {
			source.sendFailure(Component.translatableWithFallback("commands.aibuddy.spawn.failed",
					"スポーンできなかった"));
			return 0;
		}
		member.ensureAttached(); // -> BrainManager.attach(member)
		source.sendSuccess(() -> Component.translatableWithFallback("commands.aibuddy.spawn.success",
				"%sをスポーンした", persona.displayName()), true);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx, String target) {
		CommandSourceStack source = ctx.getSource();
		boolean all = target.equalsIgnoreCase("all");
		int count = 0;
		for (FakeMember m : allMembers(source.getServer())) {
			if (all || m.getPersonaId().equals(target)) {
				m.discard(); // onRemoval -> BrainManager.detach
				count++;
			}
		}
		if (count == 0) {
			source.sendFailure(Component.translatableWithFallback("commands.aibuddy.remove.none",
					"該当するメンバーがいない: %s", target));
			return 0;
		}
		final int removed = count;
		source.sendSuccess(() -> Component.translatableWithFallback("commands.aibuddy.remove.success",
				"%s人を削除した", removed), true);
		return count;
	}

	private static int melt(CommandContext<CommandSourceStack> ctx, float value) {
		List<FakeMember> members = allMembers(ctx.getSource().getServer());
		for (FakeMember m : members) {
			m.setMelt(value);
		}
		final int n = members.size();
		ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.aibuddy.melt.success",
				"溶けを%sにした（%s人）", value, n), true);
		return n;
	}

	private static int freeze(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		FakeMember.setMeltFrozen(enabled);
		ctx.getSource().sendSuccess(() -> Component.translatableWithFallback(
				enabled ? "commands.aibuddy.freeze.on" : "commands.aibuddy.freeze.off",
				enabled ? "溶けを止めた" : "溶けを再開した"), true);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		BrainManager.reload();
		ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.aibuddy.reload.success",
				"設定を再読込した"), true);
		return 1;
	}

	// ---- /aibuddy run （W3: 自動進行） ----

	private static int runEnable(CommandContext<CommandSourceStack> ctx, boolean on) {
		List<FakeMember> members = allMembers(ctx.getSource().getServer());
		for (FakeMember m : members) {
			m.autopilot().setEnabled(on);
		}
		final int n = members.size();
		ctx.getSource().sendSuccess(() -> Component.translatableWithFallback(
				on ? "commands.aibuddy.run.on" : "commands.aibuddy.run.off",
				on ? "自動進行をオンにした（%s人）" : "自動進行をオフにした（%s人）", n), true);
		return n;
	}

	private static int runStatus(CommandContext<CommandSourceStack> ctx) {
		List<FakeMember> members = allMembers(ctx.getSource().getServer());
		if (members.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatableWithFallback("commands.aibuddy.run.none", "メンバーがいない"));
			return 0;
		}
		for (FakeMember m : members) {
			String line = m.autopilot().statusLine();
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return members.size();
	}

	private static int runSkip(CommandContext<CommandSourceStack> ctx, String id) {
		Stage st = Stage.byId(id);
		if (st == null) {
			ctx.getSource().sendFailure(Component.translatableWithFallback("commands.aibuddy.run.unknown_stage",
					"知らない段階: %s", id));
			return 0;
		}
		List<FakeMember> members = allMembers(ctx.getSource().getServer());
		for (FakeMember m : members) {
			m.autopilot().skipTo(st);
		}
		final int n = members.size();
		ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.aibuddy.run.skipto",
				"「%s」から始める（%s人）", st.label, n), true);
		return n;
	}

	private static List<FakeMember> allMembers(MinecraftServer server) {
		List<FakeMember> out = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			out.addAll(level.getEntities(ModEntities.FAKE_MEMBER, m -> true));
		}
		return out;
	}
}
