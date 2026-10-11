package blockplan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BlockplanMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("blockplan");
	static Path dir;

	@Override
	public void onInitialize() {
		dir = FabricLoader.getInstance().getConfigDir().resolve("blockplan");
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			loadSaved();
			WebServer.start(server);
			Updater.check();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> WebServer.stop());
		ServerTickEvents.END_SERVER_TICK.register(Tracker::tick);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp) {
				BlockPos p = hit.getBlockPos();
				Tracker.queueCheck(sp, p, p.relative(hit.getDirection()));
			}
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp) Tracker.onBreak(sp, pos);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> registerCommands(dispatcher));
	}

	static String version() {
		return FabricLoader.getInstance().getModContainer("blockplan").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/* ---- Speichern und Laden ---- */
	static void loadSaved() {
		try {
			Path pf = dir.resolve("plan.bin");
			if (Files.exists(pf)) Tracker.setPlan(Plan.read(Files.readAllBytes(pf)));
			Path af = dir.resolve("anker.json");
			if (Files.exists(af)) {
				JsonObject o = JsonParser.parseString(Files.readString(af, StandardCharsets.UTF_8)).getAsJsonObject();
				Tracker.setAnchor(new Tracker.Anchor(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("rot").getAsInt()));
			}
		} catch (Exception e) {
			LOG.error("Blockplan: gespeicherter Plan konnte nicht geladen werden", e);
		}
	}

	static void savePlan(byte[] raw) {
		try { Files.createDirectories(dir); Files.write(dir.resolve("plan.bin"), raw); }
		catch (Exception e) { LOG.error("Blockplan: Plan konnte nicht gespeichert werden", e); }
	}

	static void saveAnchor(Tracker.Anchor a) {
		try {
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("x", a.x()); o.addProperty("y", a.y()); o.addProperty("z", a.z()); o.addProperty("rot", a.rot());
			Files.writeString(dir.resolve("anker.json"), o.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) { LOG.error("Blockplan: Anker konnte nicht gespeichert werden", e); }
	}

	static void setAnchor(Tracker.Anchor a) {
		Tracker.setAnchor(a);
		saveAnchor(a);
	}

	/* ---- Befehle ---- */
	private static int reply(CommandContext<CommandSourceStack> c, String text, ChatFormatting color) {
		c.getSource().sendSuccess(() -> Component.literal(text).withStyle(color), false);
		return 1;
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("blockplan")
				.executes(c -> reply(c, Tracker.summary(), ChatFormatting.AQUA))
				.then(Commands.literal("web").executes(c -> reply(c, "Website: http://localhost:" + WebServer.PORT + " (nur auf diesem PC, solange die Welt offen ist)", ChatFormatting.AQUA)))
				.then(Commands.literal("anker")
						.then(Commands.literal("hier").executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							BlockPos b = p.blockPosition();
							Tracker.Anchor old = Tracker.anchor;
							setAnchor(new Tracker.Anchor(b.getX(), b.getY(), b.getZ(), old == null ? 0 : old.rot()));
							return reply(c, "Plan-Ecke (Nord-West, unten) gesetzt auf " + b.getX() + " " + b.getY() + " " + b.getZ(), ChatFormatting.GREEN);
						}))
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("y", IntegerArgumentType.integer())
										.then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
											int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"), z = IntegerArgumentType.getInteger(c, "z");
											Tracker.Anchor old = Tracker.anchor;
											setAnchor(new Tracker.Anchor(x, y, z, old == null ? 0 : old.rot()));
											return reply(c, "Plan-Ecke gesetzt auf " + x + " " + y + " " + z, ChatFormatting.GREEN);
										})))))
				.then(Commands.literal("drehen")
						.then(Commands.argument("grad", IntegerArgumentType.integer(0, 270)).executes(c -> {
							int g = IntegerArgumentType.getInteger(c, "grad");
							if (g % 90 != 0) return reply(c, "Nur 0, 90, 180 oder 270.", ChatFormatting.RED);
							Tracker.Anchor a = Tracker.anchor;
							if (a == null) return reply(c, "Erst die Ecke setzen: /blockplan anker hier", ChatFormatting.RED);
							setAnchor(new Tracker.Anchor(a.x(), a.y(), a.z(), g / 90));
							return reply(c, "Plan um " + g + "° im Uhrzeigersinn gedreht.", ChatFormatting.GREEN);
						})))
				.then(Commands.literal("check").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					HitResult hit = p.pick(20.0, 1.0f, false);
					if (!(hit instanceof BlockHitResult bh) || hit.getType() == HitResult.Type.MISS) return reply(c, "Schau auf einen Block.", ChatFormatting.GRAY);
					c.getSource().sendSuccess(() -> Tracker.lookup(c.getSource().getServer().overworld(), bh.getBlockPos()), false);
					return 1;
				}))
				.then(Commands.literal("warnungen")
						.then(Commands.literal("an").executes(c -> { Tracker.muted.remove(c.getSource().getPlayerOrException().getUUID()); return reply(c, "Warnungen an.", ChatFormatting.GREEN); }))
						.then(Commands.literal("aus").executes(c -> { Tracker.muted.add(c.getSource().getPlayerOrException().getUUID()); return reply(c, "Warnungen aus.", ChatFormatting.YELLOW); }))));
	}
}
