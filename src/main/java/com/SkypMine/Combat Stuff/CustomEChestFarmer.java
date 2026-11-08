package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor's EChestFarmer + walking to the obsidian it drops (then back to where you started) so nothing is left on the ground,
 * and a "fill inventory" mode (1 echest = 8 obsidian) that keeps going until your inventory can't hold another 8.
 *
 * Look at the block you want to farm on, then enable the module.
 */
public class CustomEChestFarmer extends Module {

	private static final int OBSIDIAN_PER_ECHEST = 8;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgPickup = settings.createGroup("Pickup");
	private final SettingGroup sgRender = settings.createGroup("Render");

	// ---- general ----
	private final Setting<Boolean> fillInventory = sgGeneral.add(new BoolSetting.Builder()
			.name("fill-inventory")
			.description(
					"Farm until your inventory can't hold another echest's worth of obsidian (1 echest = 8 obsidian). Ignores the amount below.")
			.defaultValue(true).build());
	private final Setting<Boolean> selfToggle = sgGeneral.add(new BoolSetting.Builder()
			.name("self-toggle").description("Disables when you reach the desired amount of obsidian.")
			.defaultValue(true).visible(() -> !fillInventory.get()).build());
	private final Setting<Boolean> ignoreExisting = sgGeneral.add(new BoolSetting.Builder()
			.name("ignore-existing")
			.description("Ignores existing obsidian in your inventory and mines the total target amount.")
			.defaultValue(true).visible(() -> !fillInventory.get() && selfToggle.get()).build());
	private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
			.name("amount").description("The amount of obsidian to farm.")
			.defaultValue(256).range(8, 2304).sliderRange(8, 512)
			.visible(() -> !fillInventory.get() && selfToggle.get()).build());
	private final Setting<Boolean> useShulkers = sgGeneral.add(new BoolSetting.Builder()
			.name("refill-from-shulker")
			.description(
					"If you are out of echests, place a shulker that has echests, take them, break it and pick it back up.")
			.defaultValue(true).build());
	private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
			.name("swing-hand").description("Swing hand client-side.").defaultValue(true).build());
	private final Setting<Boolean> instantRebreak = sgGeneral.add(new BoolSetting.Builder()
			.name("instant-rebreak")
			.description(
					"Built-in instant rebreak: after the first echest is mined normally, every new echest on that spot is broken instantly with a stop-destroy packet. You don't need the Instant Rebreak module on. Falls back to normal mining if the server doesn't allow it.")
			.defaultValue(true).build());
	private final Setting<Integer> rebreakDelay = sgGeneral.add(new IntSetting.Builder()
			.name("rebreak-delay").description("Ticks between instant rebreak attempts (0 = every tick).")
			.defaultValue(0).min(0).max(20).sliderMax(10).visible(instantRebreak::get).build());

	// ---- pickup ----
	private final Setting<Boolean> pickupDrops = sgPickup.add(new BoolSetting.Builder()
			.name("pickup-drops")
			.description(
					"Walk to the obsidian that drops from each echest to pick it up, then go back to where you started. The echest spot is locked to the block you were looking at when you enabled the module.")
			.defaultValue(true).build());
	private final Setting<Boolean> breakAllFirst = sgPickup.add(new BoolSetting.Builder()
			.name("break-all-first")
			.description(
					"Break ALL the echests first (the fill-inventory / amount calculation decides how many), and only then walk around and pick up all the obsidian. Never does both at once. Overrides echests-per-pickup.")
			.defaultValue(true).visible(pickupDrops::get).build());
	private final Setting<Integer> echestsPerPickup = sgPickup.add(new IntSetting.Builder()
			.name("echests-per-pickup")
			.description(
					"Break this many echests first (8 obsidian each), then go pick up all the obsidian in one trip. 8 echests = 64 obsidian = 1 stack.")
			.defaultValue(8).range(1, 64).sliderRange(1, 32)
			.visible(() -> pickupDrops.get() && !breakAllFirst.get()).build());
	private final Setting<Double> pickupRange = sgPickup.add(new DoubleSetting.Builder()
			.name("pickup-range").description("How far from the echest spot it will go to grab dropped obsidian.")
			.defaultValue(6).min(2).max(30).sliderMax(15).visible(pickupDrops::get).build());

	// ---- render ----
	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
			.name("shape-mode").description("How the shapes are rendered.").defaultValue(ShapeMode.Both).build());
	private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
			.name("side-color").description("The color of the sides of the blocks being rendered.")
			.defaultValue(new SettingColor(204, 0, 255, 10)).build());
	private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
			.name("line-color").description("The color of the lines of the blocks being rendered.")
			.defaultValue(new SettingColor(204, 0, 255, 255)).build());

	private enum Refill {
		NONE,
		PLACING,
		WAIT_PLACED,
		OPENING,
		WAIT_OPEN,
		BREAKING,
		WAIT_PICKUP,
		RETURNING
	}

	private BlockPos target;
	private int startCount;
	private int drainTicks;
	private Vec3 home; // where you stood when you enabled the module
	private int brokenCount, collectTicks;
	private boolean collecting, wasChest;
	private BlockPos rebreakPos; // spot that has already been mined once (server remembers it)
	private int rebreakTimer, rebreakSends;
	private boolean rebreakFailed;

	private Refill refill = Refill.NONE;
	private Refill lastRefill = Refill.NONE;
	private BlockPos shulkerPos;
	private Vec3 refillOrigin; // where you stood when the refill started
	private int refillTimer, openSince, pickupTimer, stepAge;

	public CustomEChestFarmer() {
		super(Addon.CATEGORY, "custom-echest-farmer",
				"Places and breaks EChests to farm obsidian, circling around so you pick it all up.");
	}

	@Override
	public void onActivate() {
		if (mc.player == null || mc.level == null)
			return;
		startCount = InvUtils.find(Items.OBSIDIAN).count();
		drainTicks = 0;
		home = mc.player.position();
		brokenCount = 0;
		collectTicks = 0;
		collecting = false;
		wasChest = false;
		rebreakPos = null;
		rebreakTimer = 0;
		rebreakSends = 0;
		rebreakFailed = false;
		updateTargetFromCrosshair();

		if (pickupDrops.get() && target == null) {
			error("Look at a block (or an echest) to farm on first.");
			toggle();
			return;
		}

		if (fillInventory.get()) {
			int room = obsidianCapacity();
			int needed = (room + OBSIDIAN_PER_ECHEST - 1) / OBSIDIAN_PER_ECHEST;
			info("Room for %d obsidian = %d echests to fill up (1 echest = 8 obsidian). You have %d.",
					room, needed, InvUtils.find(Items.ENDER_CHEST).count());
		}
	}

	@Override
	public void onDeactivate() {
		InvUtils.swapBack();
		stopMoving();
		if (mc.player != null && mc.player.containerMenu instanceof ShulkerBoxMenu)
			mc.player.closeContainer();
		refill = Refill.NONE;
		lastRefill = Refill.NONE;
		shulkerPos = null;
		refillOrigin = null;
		collecting = false;
		brokenCount = 0;
		rebreakPos = null;
		target = null;
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null)
			return;

		// Shulker refill in progress: it takes over until it's finished
		if (runRefill())
			return;

		// Done? Stop placing, but keep walking until the last drops are picked up.
		if (finished()) {
			boolean allFirst = pickupDrops.get() && breakAllFirst.get();
			if (pickupDrops.get() && target != null && (groundObsidian() > 0 || (allFirst && !atHome()))
					&& ++drainTicks < (allFirst ? 600 : 80)) {
				collectStep();
				return;
			}
			stopMoving();
			info("Done - obsidian farmed.");
			toggle();
			return;
		}

		// Target: locked while circling, follows the crosshair otherwise
		if (!pickupDrops.get() || target == null)
			updateTargetFromCrosshair();
		if (target == null)
			return;

		BlockState state = mc.level.getBlockState(target);

		// count echests as they get broken
		boolean isChest = state.getBlock() == Blocks.ENDER_CHEST;
		if (wasChest && !isChest) {
			brokenCount++;
			rebreakSends = 0; // the last break went through
			if (instantRebreak.get() && !rebreakFailed)
				rebreakPos = target.immutable();
		}
		wasChest = isChest;

		// enough broken: stop farming and pick everything up in one trip, then go back home
		if (pickupDrops.get()) {
			if (!breakAllFirst.get() && !collecting && brokenCount >= echestsPerPickup.get()) {
				collecting = true;
				collectTicks = 0;
			}
			if (collecting) {
				collectStep();
				collectTicks++;
				boolean nothingLeft = collectTicks > 8 && nearestDrop() == null && atHome(); // 8 ticks for drops to appear
				if (nothingLeft || collectTicks > 300) {
					collecting = false;
					brokenCount = 0;
					collectTicks = 0;
					stopMoving();
				}
				return;
			}
		}

		// Break existing echest
		if (state.getBlock() == Blocks.ENDER_CHEST) {
			double bestScore = -1;
			int bestSlot = -1;

			for (int i = 0; i < 9; i++) {
				ItemStack itemStack = mc.player.getInventory().getItem(i);
				if (Utils.hasEnchantment(itemStack, Enchantments.SILK_TOUCH))
					continue; // silk touch would give the chest back

				double score = itemStack.getDestroySpeed(Blocks.ENDER_CHEST.defaultBlockState());
				if (score > bestScore) {
					bestScore = score;
					bestSlot = i;
				}
			}

			if (bestSlot == -1)
				return;

			InvUtils.swap(bestSlot, true);

			if (instantRebreak.get() && !rebreakFailed && target.equals(rebreakPos)) {
				if (rebreakTimer > 0)
					rebreakTimer--;
				else if (++rebreakSends > 12) {
					// 12 attempts and the echest is still there: the server doesn't allow it
					rebreakFailed = true;
					rebreakPos = null;
					info("Server ignored instant rebreak - using normal mining.");
				} else {
					sendRebreak(target);
					rebreakTimer = rebreakDelay.get();
				}
			} else {
				BlockUtils.breakBlock(target, swingHand.get());
			}
		}
		// Place echest
		else if (state.canBeReplaced()) {
			FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);

			if (!echest.found()) {
				// pull a stack from the inventory into the hotbar
				FindItemResult inv = InvUtils.find(Items.ENDER_CHEST);
				if (inv.found() && !inv.isHotbar()) {
					InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot());
					return;
				}
				if (pickupDrops.get() && brokenCount > 0) {
					collecting = true;
					collectTicks = 0;
					return;
				} // grab the drops first
				if (useShulkers.get() && startShulkerRefill())
					return;
				error("No Echests in hotbar, disabling");
				toggle();
				return;
			}

			if (!PlayerUtils.isWithinReach(target)) {
				if (pickupDrops.get())
					return; // walking back home brings you into reach
				error("Target block pos out of reach.");
				toggle();
				return;
			}

			BlockUtils.place(target, echest, true, 0, swingHand.get());
		}
	}

	@EventHandler
	private void onRender(Render3DEvent event) {
		if (target == null)
			return;
		event.renderer.box(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
	}

	// ================= helpers =================

	private void updateTargetFromCrosshair() {
		target = null;
		if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = bhr.getBlockPos();
			if (mc.level.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
				target = pos.immutable();
			} else {
				pos = pos.above();
				if (mc.level.getBlockState(pos).canBeReplaced())
					target = pos.immutable();
			}
		}
	}

	private static java.lang.reflect.Method predictionMethod;

	/** getBlockStatePredictionHandler() isn't public, so it is looked up by name. */
	private static BlockStatePredictionHandler predictionHandler(ClientLevel level) throws Exception {
		if (predictionMethod == null) {
			predictionMethod = ClientLevel.class.getDeclaredMethod("getBlockStatePredictionHandler");
			predictionMethod.setAccessible(true);
		}
		return (BlockStatePredictionHandler) predictionMethod.invoke(level);
	}

	/** Same trick as Meteor's Instant Rebreak: a stop-destroy packet for a spot the server already saw you mine. */
	private void sendRebreak(BlockPos pos) {
		if (mc.getConnection() == null || mc.level == null)
			return;
		if (swingHand.get())
			mc.player.swing(InteractionHand.MAIN_HAND);
		try (BlockStatePredictionHandler handler = predictionHandler(mc.level).startPredicting()) {
			mc.getConnection().send(new ServerboundPlayerActionPacket(
					ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP,
					handler.currentSequence()));
		} catch (Exception e) {
			rebreakFailed = true;
			rebreakPos = null;
			info("Instant rebreak isn't available in this version - using normal mining.");
		}
	}

	private boolean finished() {
		if (fillInventory.get())
			return obsidianCapacity() - groundObsidian() < OBSIDIAN_PER_ECHEST;
		if (!selfToggle.get())
			return false;
		int count = InvUtils.find(Items.OBSIDIAN).count();
		if (pickupDrops.get() && breakAllFirst.get())
			count += groundObsidian(); // still on the ground, will be picked up at the end
		return count - (ignoreExisting.get() ? startCount : 0) >= amount.get();
	}

	/** How much more obsidian the inventory can hold (empty slots + room left in obsidian stacks). */
	private int obsidianCapacity() {
		int room = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = mc.player.getInventory().getItem(i);
			if (st.isEmpty())
				room += 64;
			else if (st.is(Items.OBSIDIAN))
				room += st.getMaxStackSize() - st.getCount();
		}
		return room;
	}

	/** Obsidian lying on the ground nearby that hasn't been picked up yet. */
	private int groundObsidian() {
		int total = 0;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof ItemEntity ie && ie.isAlive() && ie.getItem().is(Items.OBSIDIAN)
					&& ie.distanceToSqr(mc.player) < 100)
				total += ie.getItem().getCount();
		}
		return total;
	}

	private int freeHotbarSlot() {
		for (int i = 0; i < 9; i++)
			if (mc.player.getInventory().getItem(i).isEmpty())
				return i;
		return mc.player.getInventory().getSelectedSlot();
	}

	// ================= echest refill from a shulker =================

	private boolean isShulkerWithEchests(ItemStack st) {
		if (!(Block.byItem(st.getItem()) instanceof ShulkerBoxBlock))
			return false;
		var contents = st.get(DataComponents.CONTAINER);
		if (contents == null)
			return false;

		// 1) read the stored items directly (their toString names the item)
		try {
			for (var t : contents.nonEmptyItems()) {
				if (String.valueOf(t).contains("ender_chest"))
					return true;
			}
		} catch (Throwable ignored) {
		}

		// 2) fallback: serialize the whole stack and look for the echest id
		try {
			return ItemStack.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, st)
					.result().map(tag -> tag.toString().contains("ender_chest")).orElse(false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean startShulkerRefill() {
		if (refill != Refill.NONE)
			return true;
		if (!InvUtils.find(this::isShulkerWithEchests).found())
			return false;
		stopMoving(); // stop circling while the shulker is handled
		refillOrigin = mc.player.position();
		refill = Refill.PLACING;
		refillTimer = 0;
		openSince = -1;
		info("Out of echests - taking some from a shulker.");
		return true;
	}

	private void resetRefill() {
		stopMoving();
		refill = Refill.NONE;
		lastRefill = Refill.NONE;
		shulkerPos = null;
		refillOrigin = null;
	}

	private void walkTo(Vec3 to) {
		double dx = to.x - mc.player.getX();
		double dz = to.z - mc.player.getZ();
		if (dx * dx + dz * dz < 0.15) {
			stopMoving();
			return;
		} // basically on top of it
		mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(mc.player.horizontalCollision || to.y - mc.player.getY() > 0.6);
	}

	private BlockPos findShulkerSpot() {
		BlockPos feet = mc.player.blockPosition();
		Vec3 eye = mc.player.getEyePosition();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos pos = feet.offset(dx, 0, dz);
				if (pos.equals(feet) || pos.equals(feet.above()))
					continue;
				if (target != null && (pos.equals(target) || pos.equals(target.above())))
					continue; // keep the echest spot free
				BlockPos below = pos.below();
				if (!mc.level.getBlockState(pos).isAir() || !mc.level.getBlockState(pos.above()).isAir())
					continue;
				if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP))
					continue;
				double d = pos.getCenter().distanceToSqr(eye);
				if (d < bd && d < 20) {
					bd = d;
					best = pos;
				}
			}
		}
		return best;
	}

	/** Runs the shulker refill steps. Returns true while it is busy (farming pauses). */
	private boolean runRefill() {
		if (refill == Refill.NONE)
			return false;
		if (refill != lastRefill) {
			lastRefill = refill;
			stepAge = 0;
		}
		stepAge++;

		if (++refillTimer > 400) {
			info("Shulker refill timed out.");
			if (mc.player.containerMenu instanceof ShulkerBoxMenu)
				mc.player.closeContainer();
			resetRefill();
			return false;
		}

		switch (refill) {
			case PLACING -> {
				FindItemResult sh = InvUtils.find(this::isShulkerWithEchests);
				if (!sh.found()) {
					resetRefill();
					return false;
				}
				if (!sh.isHotbar()) {
					InvUtils.move().from(sh.slot()).toHotbar(freeHotbarSlot());
					return true;
				}
				BlockPos spot = findShulkerSpot();
				if (spot == null) {
					info("No free spot for the shulker.");
					resetRefill();
					return false;
				}
				InvUtils.swap(sh.slot(), false);
				BlockPos support = spot.below();
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
						new BlockHitResult(new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5), Direction.UP,
								support, false));
				mc.player.swing(InteractionHand.MAIN_HAND);
				shulkerPos = spot;
				refill = Refill.WAIT_PLACED;
			}
			case WAIT_PLACED -> {
				if (mc.level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock)
					refill = Refill.OPENING;
				else if (stepAge > 12)
					refill = Refill.PLACING; // placement didn't go through, try again
			}
			case OPENING -> {
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
						new BlockHitResult(shulkerPos.getCenter(), Direction.UP, shulkerPos, false));
				openSince = -1;
				refill = Refill.WAIT_OPEN;
			}
			case WAIT_OPEN -> {
				if (mc.player.containerMenu instanceof ShulkerBoxMenu menu) {
					if (openSince < 0)
						openSince = refillTimer;
					if (refillTimer - openSince < 3)
						return true; // let the contents arrive
					for (int i = 0; i < 27; i++) {
						if (menu.getSlot(i).getItem().is(Items.ENDER_CHEST))
							mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE,
									mc.player);
					}
					mc.player.closeContainer();
					refill = Refill.BREAKING;
				} else if (stepAge > 20)
					refill = Refill.OPENING; // didn't open, click it again
			}
			case BREAKING -> {
				if (mc.level.getBlockState(shulkerPos).isAir()) {
					pickupTimer = 100;
					refill = Refill.WAIT_PICKUP;
				} else {
					FindItemResult pick = InvUtils.findInHotbar(st -> st.is(ItemTags.PICKAXES));
					if (pick.found())
						InvUtils.swap(pick.slot(), false);
					BlockUtils.breakBlock(shulkerPos, true);
				}
			}
			case WAIT_PICKUP -> {
				ItemEntity drop = null;
				double bd = 64; // within 8 blocks of you
				for (Entity e : mc.level.entitiesForRendering()) {
					if (e instanceof ItemEntity ie && ie.isAlive()
							&& Block.byItem(ie.getItem().getItem()) instanceof ShulkerBoxBlock) {
						double d = ie.distanceToSqr(mc.player);
						if (d < bd) {
							bd = d;
							drop = ie;
						}
					}
				}
				// no drop visible after the first few ticks = already picked up
				if ((drop == null && pickupTimer < 90) || --pickupTimer <= 0) {
					stopMoving();
					refill = Refill.RETURNING; // got it (or gave up): head back to where you were
				} else if (drop != null)
					walkTo(drop.position());
			}
			case RETURNING -> {
				if (refillOrigin == null) {
					resetRefill();
					return false;
				}
				double dx = refillOrigin.x - mc.player.getX();
				double dz = refillOrigin.z - mc.player.getZ();
				if (dx * dx + dz * dz < 0.2 || stepAge > 120) {
					resetRefill();
					return false;
				}
				walkTo(refillOrigin);
			}
			default -> {
			}
		}
		return true;
	}

	/** Nearest dropped obsidian within pickup-range of the echest spot. */
	private ItemEntity nearestDrop() {
		double cx = target.getX() + 0.5, cz = target.getZ() + 0.5;
		double range = pickupRange.get();
		ItemEntity drop = null;
		double best = Double.MAX_VALUE;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity ie) || !ie.isAlive() || !ie.getItem().is(Items.OBSIDIAN))
				continue;
			double dxs = ie.getX() - cx, dzs = ie.getZ() - cz;
			if (dxs * dxs + dzs * dzs > range * range)
				continue; // too far from the echest spot
			double d = ie.distanceToSqr(mc.player);
			if (d < best) {
				best = d;
				drop = ie;
			}
		}
		return drop;
	}

	private boolean atHome() {
		if (home == null)
			return true;
		double dx = home.x - mc.player.getX(), dz = home.z - mc.player.getZ();
		return dx * dx + dz * dz < 0.2;
	}

	/** Walks to the nearest dropped obsidian; with nothing left on the ground, goes back home and stands still. */
	private void collectStep() {
		ItemEntity drop = nearestDrop();
		if (drop != null)
			walkTo(drop.position());
		else if (home != null)
			walkTo(home);
		else
			stopMoving();
	}

	private void stopMoving() {
		if (mc.options == null)
			return;
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
	}
}
