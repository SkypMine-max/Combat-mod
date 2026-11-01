package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Fast eat / drink (food and potions).
 *  - Singleplayer / LAN host: reaches into the integrated server and sets the eating timer of your player to
 *    eat-ticks, so 1 = you finish eating on the very next game tick. This is the only way to get a true 1-tick eat.
 *  - Packets: spams movement packets. Only works on old / unpatched servers.
 */
public class FastEat extends Module {

	public enum Mode {
		Auto,
		Singleplayer,
		Packets
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();

	private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
			.name("mode")
			.description("Auto = Singleplayer when you are in a singleplayer/LAN world, Packets otherwise.")
			.defaultValue(Mode.Auto)
			.build());

	private final Setting<Integer> eatTicks = sgGeneral.add(new IntSetting.Builder()
			.name("eat-ticks")
			.description("Game ticks left when eating starts to finish (vanilla is 32). 1 = next tick.")
			.defaultValue(1)
			.range(1, 5)
			.sliderRange(1, 5)
			.build());

	private final Setting<Boolean> instant = sgGeneral.add(new BoolSetting.Builder()
			.name("instant")
			.description("Singleplayer/LAN only: finish eating in the same tick you start (ignores eat-ticks).")
			.defaultValue(true)
			.build());

	private final Setting<Boolean> onlyWhenHungry = sgGeneral.add(new BoolSetting.Builder()
			.name("only-when-hungry")
			.description("Only runs if your hunger bar isn't full (golden apples still work when you are full).")
			.defaultValue(false)
			.build());

	private static Field useField;
	private static boolean fieldMissing;
	private static Method finishMethod;
	private static boolean finishMissing;
	private boolean warned;

	public FastEat() {
		super(Addon.CATEGORY, "fast-eat", "Eats food and drinks potions in 1-5 ticks.");
	}

	@Override
	public void onActivate() {
		warned = false;
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.getConnection() == null)
			return;
		if (!mc.player.isUsingItem())
			return;

		ItemStack stack = mc.player.getUseItem();
		boolean isFood = stack.has(DataComponents.FOOD);
		// food AND potions/milk/honey (anything you hold right click to consume)
		if (!isFood && !stack.has(DataComponents.CONSUMABLE))
			return;

		boolean canAlwaysEat = stack.is(net.minecraft.world.item.Items.GOLDEN_APPLE)
				|| stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE);
		if (onlyWhenHungry.get() && isFood && !canAlwaysEat && !mc.player.getFoodData().needsFood())
			return;

		MinecraftServer server = mc.getSingleplayerServer();
		boolean useServer = switch (mode.get()) {
			case Singleplayer -> true;
			case Packets -> false;
			case Auto -> server != null;
		};

		if (useServer) {
			if (server == null) {
				if (!warned) {
					warned = true;
					warning("Singleplayer mode only works in a singleplayer/LAN world. Switch to Packets or Auto.");
				}
				return;
			}
			shortenOnServer(server);
		} else {
			sendPackets(stack);
		}
	}

	/** Integrated server only: lowers the eating timer of your player so the meal finishes in eat-ticks. */
	private void shortenOnServer(MinecraftServer server) {
		if (fieldMissing)
			return;
		UUID id = mc.player.getUUID();
		int ticks = eatTicks.get();

		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp == null || !sp.isUsingItem())
				return;
			try {
				if (instant.get() && !finishMissing) {
					if (finishMethod == null)
						finishMethod = findFinishMethod(sp.getClass());
					if (finishMethod != null) {
						finishMethod.invoke(sp); // same code vanilla runs when the eating timer hits 0
						return;
					}
					finishMissing = true;
				}
				if (useField == null)
					useField = findUseField(sp.getClass());
				if (useField == null) {
					fieldMissing = true;
					return;
				}
				if (useField.getInt(sp) > ticks)
					useField.setInt(sp, ticks);
			} catch (Throwable t) {
				fieldMissing = true;
			}
		});

		if (fieldMissing && !warned) {
			warned = true;
			error("Couldn't find the eating timer field (useItemRemaining) - this Minecraft version may use different names.");
		}
	}

	private static Method findFinishMethod(Class<?> c) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Method m = k.getDeclaredMethod("completeUsingItem");
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static Field findUseField(Class<?> c) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField("useItemRemaining");
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	/** Multiplayer: old-server packet trick. Modern / anticheat servers ignore it. */
	private void sendPackets(ItemStack stack) {
		int total = stack.getUseDuration(mc.player);
		int packetsPerTick = (int) Math.ceil(total / (double) eatTicks.get());

		for (int i = 0; i < packetsPerTick; i++) {
			mc.getConnection().send(
					new ServerboundMovePlayerPacket.StatusOnly(mc.player.onGround(), mc.player.horizontalCollision));
		}
	}
}
