package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * TNT Maniac: fills the area around you with TNT. Lighting is manual (hold the light key).
 *
 * TNT is placed at random spots, never within min-gap of another TNT (so there are always
 * lanes you can walk through), and a clear zone around you is never filled.
 */
public class TntManiac extends Module {

	private final SettingGroup sgPlace = settings.getDefaultGroup();
	private final SettingGroup sgLight = settings.createGroup("Light");
	private final SettingGroup sgScaffold = settings.createGroup("Scaffold");
	private final SettingGroup sgShulker = settings.createGroup("Shulker");

	// ---- placing ----
	private final Setting<Integer> range = sgPlace.add(new IntSetting.Builder()
			.name("range").description("How far around you to place TNT (blocks).")
			.defaultValue(5).min(2).sliderMax(6).build());
	private final Setting<Integer> minGap = sgPlace.add(new IntSetting.Builder()
			.name("min-gap")
			.description(
					"Free blocks kept between TNT (sideways, same height). 1 = always a walkable lane around every TNT.")
			.defaultValue(1).min(1).sliderMax(4).build());
	private final Setting<Integer> clearRadius = sgPlace.add(new IntSetting.Builder()
			.name("clear-radius")
			.description("Keeps this many blocks around you completely free of TNT so you can always walk.")
			.defaultValue(1).min(0).sliderMax(4).build());
	private final Setting<Integer> height = sgPlace.add(new IntSetting.Builder()
			.name("height").description("How many blocks high to stack TNT (starting at your feet level).")
			.defaultValue(2).min(1).sliderMax(5).build());
	private final Setting<Boolean> airPlace = sgPlace.add(new BoolSetting.Builder()
			.name("air-place")
			.description("Place TNT in mid-air with nothing to click on. Needs a server that allows it.")
			.defaultValue(true).build());
	private final Setting<Integer> perTick = sgPlace.add(new IntSetting.Builder()
			.name("max-per-round").description("Up to this many TNT per round (random 1 to this number).")
			.defaultValue(8).min(1).sliderMax(20).build());
	private final Setting<Integer> minDelay = sgPlace.add(new IntSetting.Builder()
			.name("min-delay").description("Min ticks between placing rounds (random between min and max).")
			.defaultValue(0).min(0).sliderMax(20).build());
	private final Setting<Integer> maxDelay = sgPlace.add(new IntSetting.Builder()
			.name("max-delay").description("Max ticks between placing rounds.")
			.defaultValue(0).min(0).sliderMax(20).build());
	private final Setting<Boolean> swing = sgPlace.add(new BoolSetting.Builder()
			.name("swing").description("Swing your arm when placing / lighting.").defaultValue(true).build());

	// ---- lighting (manual only: nothing is ever lit unless you press the key) ----
	private final Setting<Keybind> lightKey = sgLight.add(new KeybindSetting.Builder()
			.name("light-key").description("Hold this key to light the TNT near you. TNT is never lit automatically.")
			.defaultValue(Keybind.none()).build());
	private final Setting<Integer> lightsPerTick = sgLight.add(new IntSetting.Builder()
			.name("lights-per-tick").description("How many TNT to light each tick while the key is held.")
			.defaultValue(2).min(1).sliderMax(10).build());
	private final Setting<Double> lightMinDistance = sgLight.add(new DoubleSetting.Builder()
			.name("min-light-distance").description("Skip TNT closer than this to you (0 = light everything in reach).")
			.defaultValue(0.0).min(0).sliderMax(8).build());

	// ---- scaffold ----
	private final Setting<Boolean> scaffold = sgScaffold.add(new BoolSetting.Builder()
			.name("scaffold")
			.description("Places a block from scaffold-blocks (TNT by default) under your feet and a bit ahead of you.")
			.defaultValue(true).build());
	private final Setting<List<Block>> scaffoldBlocks = sgScaffold.add(new BlockListSetting.Builder()
			.name("scaffold-blocks")
			.description("Only these blocks are used to scaffold. TNT by default - add or remove blocks as you like.")
			.defaultValue(Blocks.TNT).build());
	private final Setting<Integer> scaffoldAhead = sgScaffold.add(new IntSetting.Builder()
			.name("scaffold-ahead")
			.description("How far ahead of your movement to bridge (in ticks of movement). 0 = only under your feet.")
			.defaultValue(3).min(0).sliderMax(8).build());

	// ---- shulker restock ----
	private final Setting<Boolean> useShulkers = sgShulker.add(new BoolSetting.Builder()
			.name("use-shulkers")
			.description(
					"Out of TNT? Places a shulker that has TNT in it, takes the TNT, breaks it and picks it back up.")
			.defaultValue(true).build());

	/** Where we placed TNT -> tick it was placed. */
	private final Map<BlockPos, Integer> placed = new HashMap<>();
	private final Random random = new Random();
	private int age;
	private int placeTimer;
	private boolean warnedNoTnt, warnedNoLighter;

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

	private Refill refill = Refill.NONE;
	private Refill lastRefill = Refill.NONE;
	private BlockPos shulkerPos;
	private Vec3 refillOrigin;
	private int refillTimer, openSince, pickupTimer, stepAge;
	private static final Pattern TNT_ID = Pattern.compile("(^|[^a-z_])tnt($|[^a-z_])");

	public TntManiac() {
		super(Addon.CATEGORY, "tnt-maniac",
				"Places TNT at random spots all around you (with walking lanes) and can air-place it. Lighting is manual (hold the light key).");
	}

	@Override
	public void onActivate() {
		placed.clear();
		age = 0;
		placeTimer = 0;
		warnedNoTnt = false;
		warnedNoLighter = false;
		resetRefill();
	}

	@Override
	public void onDeactivate() {
		placed.clear();
		if (mc.player != null && mc.player.containerMenu instanceof ShulkerBoxMenu)
			mc.player.closeContainer();
		resetRefill();
		InvUtils.swapBack();
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null)
			return;
		age++;

		// shulker restock takes over until it is done
		if (runRefill())
			return;

		if (scaffold.get())
			scaffoldRound();

		if (placeTimer > 0)
			placeTimer--;
		else
			placeRound();

		if (lightKey.get().isPressed())
			lightRound();
	}

	// ------------------------------------------------------------------ placing

	private void placeRound() {
		FindItemResult tnt = InvUtils.findInHotbar(Items.TNT);
		if (!tnt.found()) {
			FindItemResult inv = InvUtils.find(Items.TNT);
			if (inv.found()) {
				InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot());
				return; // it will be in the hotbar next tick
			}
			if (useShulkers.get() && InvUtils.find(this::isShulkerWithTnt).found()) {
				startShulkerRefill();
				return;
			}
			if (!warnedNoTnt) {
				warnedNoTnt = true;
				info("No TNT in your inventory.");
			}
			return;
		}
		warnedNoTnt = false;

		List<BlockPos> spots = findSpots();
		if (spots.isEmpty())
			return;

		int want = 1 + random.nextInt(perTick.get());
		List<BlockPos> chosen = new ArrayList<>();
		InvUtils.swap(tnt.slot(), true);
		for (BlockPos p : spots) { // already shuffled -> random spots
			if (chosen.size() >= want)
				break;
			if (tooClose(p, chosen))
				continue; // keeps lanes open, also vs. TNT picked this same round
			BlockHitResult hit = buildHit(p);
			if (hit == null)
				continue;
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
			placed.put(p, age);
			chosen.add(p);
		}
		if (!chosen.isEmpty() && swing.get())
			mc.player.swing(InteractionHand.MAIN_HAND);
		InvUtils.swapBack();

		int lo = Math.min(minDelay.get(), maxDelay.get()), hi = Math.max(minDelay.get(), maxDelay.get());
		placeTimer = lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);
	}

	/** True if p is within min-gap (sideways, same height) of existing/just-placed TNT. Stops walls and diagonal chains. */
	private boolean tooClose(BlockPos p, List<BlockPos> chosen) {
		int g = minGap.get();
		for (int dx = -g; dx <= g; dx++) {
			for (int dz = -g; dz <= g; dz++) {
				if (dx == 0 && dz == 0)
					continue;
				BlockPos q = p.offset(dx, 0, dz);
				if (placed.containsKey(q) || chosen.contains(q) || mc.level.getBlockState(q).is(Blocks.TNT))
					return true;
			}
		}
		return false;
	}

	/** Every free spot around the player, in random order. */
	private List<BlockPos> findSpots() {
		BlockPos base = mc.player.blockPosition();
		int r = range.get();
		int clear = clearRadius.get();
		double maxDistSq = (r + 0.5) * (r + 0.5);
		List<BlockPos> out = new ArrayList<>();

		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				// the clear zone around you stays empty
				if (Math.abs(dx) <= clear && Math.abs(dz) <= clear)
					continue;
				int x = base.getX() + dx, z = base.getZ() + dz;
				if (dx * dx + dz * dz > maxDistSq)
					continue;

				for (int dy = 0; dy < height.get(); dy++) {
					BlockPos p = new BlockPos(x, base.getY() + dy, z);
					if (!mc.level.getBlockState(p).isAir())
						continue;
					if (!airPlace.get() && !hasSupport(p))
						continue;
					if (entityBlocks(p))
						continue;
					out.add(p);
				}
			}
		}
		Collections.shuffle(out, random);
		return out;
	}

	private boolean entityBlocks(BlockPos p) {
		AABB box = new AABB(p);
		for (Entity e : mc.level.getEntities((Entity) null, box)) {
			if (!e.isSpectator() && !(e instanceof ItemEntity))
				return true;
		}
		return false;
	}

	private boolean hasSupport(BlockPos p) {
		for (Direction d : Direction.values()) {
			BlockPos n = p.relative(d);
			if (mc.level.getBlockState(n).isFaceSturdy(mc.level, n, d.getOpposite()))
				return true;
		}
		return false;
	}

	/** Air place = click the empty spot itself; otherwise click the face of a solid neighbour. */
	private BlockHitResult buildHit(BlockPos p) {
		return buildHit(p, false);
	}

	private BlockHitResult buildHit(BlockPos p, boolean preferSupport) {
		if (airPlace.get() && !preferSupport)
			return new BlockHitResult(p.getCenter(), Direction.UP, p, false);

		for (Direction d : Direction.values()) {
			BlockPos n = p.relative(d);
			Direction face = d.getOpposite();
			if (!mc.level.getBlockState(n).isFaceSturdy(mc.level, n, face))
				continue;
			Vec3 hitPos = n.getCenter().add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			return new BlockHitResult(hitPos, face, n, false);
		}
		// nothing to click on: air place if allowed
		if (preferSupport && airPlace.get())
			return new BlockHitResult(p.getCenter(), Direction.UP, p, false);
		return null;
	}

	// ----------------------------------------------------------------- lighting

	private void lightRound() {
		// forget TNT that is gone (exploded / broken)
		for (Iterator<Map.Entry<BlockPos, Integer>> it = placed.entrySet().iterator(); it.hasNext();) {
			BlockPos p = it.next().getKey();
			if (!mc.level.getBlockState(p).is(Blocks.TNT))
				it.remove();
		}
		if (placed.isEmpty())
			return;

		Vec3 me = mc.player.position();
		double minSq = lightMinDistance.get() * lightMinDistance.get();
		List<BlockPos> ready = new ArrayList<>();
		for (Map.Entry<BlockPos, Integer> e : placed.entrySet()) {
			if (e.getKey().getCenter().distanceToSqr(me) < minSq)
				continue;
			// reach check: the server won't accept clicks from too far
			if (e.getKey().getCenter().distanceToSqr(mc.player.getEyePosition()) > 4.5 * 4.5)
				continue;
			ready.add(e.getKey());
		}
		if (ready.isEmpty())
			return;

		FindItemResult lighter = InvUtils.findInHotbar(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
		if (!lighter.found()) {
			FindItemResult inv = InvUtils.find(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
			if (inv.found()) {
				InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot());
				return;
			}
			if (!warnedNoLighter) {
				warnedNoLighter = true;
				info("No flint and steel / fire charge to light the TNT.");
			}
			return;
		}
		warnedNoLighter = false;

		ready.sort(Comparator.comparingDouble(p -> p.getCenter().distanceToSqr(me)));
		InvUtils.swap(lighter.slot(), true);
		int n = Math.min(lightsPerTick.get(), ready.size());
		for (int i = 0; i < n; i++) {
			BlockPos p = ready.get(i);
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
					new BlockHitResult(p.getCenter(), Direction.UP, p, false));
			placed.remove(p);
		}
		if (swing.get())
			mc.player.swing(InteractionHand.MAIN_HAND);
		InvUtils.swapBack();
	}

	// ----------------------------------------------------------------- scaffold

	private void scaffoldRound() {
		List<BlockPos> targets = new ArrayList<>();
		BlockPos under = mc.player.blockPosition().below();
		targets.add(under);
		int ahead = scaffoldAhead.get();
		Vec3 dm = mc.player.getDeltaMovement();
		if (ahead > 0 && dm.x * dm.x + dm.z * dm.z > 0.0025) {
			BlockPos a = BlockPos.containing(mc.player.getX() + dm.x * ahead, under.getY(),
					mc.player.getZ() + dm.z * ahead);
			if (!a.equals(under))
				targets.add(a);
		}

		FindItemResult blocks = findScaffoldBlock();
		if (blocks == null)
			return;
		boolean swapped = false;
		for (BlockPos p : targets) {
			if (!mc.level.getBlockState(p).canBeReplaced() || entityBlocks(p))
				continue;
			BlockHitResult hit = buildHit(p, true);
			if (hit == null)
				continue;
			if (!swapped) {
				InvUtils.swap(blocks.slot(), true);
				swapped = true;
			}
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
		}
		if (swapped) {
			if (swing.get())
				mc.player.swing(InteractionHand.MAIN_HAND);
			InvUtils.swapBack();
		}
	}

	/** A block from your scaffold-blocks list (TNT by default), from the hotbar or pulled in from the inventory. */
	private FindItemResult findScaffoldBlock() {
		FindItemResult r = InvUtils.findInHotbar(st -> isScaffoldBlock(st));
		if (r.found())
			return r;
		FindItemResult inv = InvUtils.find(st -> isScaffoldBlock(st));
		if (inv.found() && !inv.isHotbar() && !inv.isOffhand())
			InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot()); // usable next tick
		return null;
	}

	private boolean isScaffoldBlock(ItemStack st) {
		if (st.isEmpty() || !(st.getItem() instanceof BlockItem bi))
			return false;
		return scaffoldBlocks.get().contains(bi.getBlock());
	}

	// ------------------------------------------------------------ shulker restock

	private boolean isShulkerWithTnt(ItemStack st) {
		if (!(Block.byItem(st.getItem()) instanceof ShulkerBoxBlock))
			return false;
		var contents = st.get(DataComponents.CONTAINER);
		if (contents == null)
			return false;
		try {
			for (var t : contents.nonEmptyItems()) {
				if (TNT_ID.matcher(String.valueOf(t)).find())
					return true;
			}
		} catch (Throwable ignored) {
		}
		try {
			return ItemStack.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, st).result()
					.map(tag -> tag.toString().contains("minecraft:tnt\"")).orElse(false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void startShulkerRefill() {
		if (refill != Refill.NONE || !InvUtils.find(this::isShulkerWithTnt).found())
			return;
		refillOrigin = mc.player.position();
		refill = Refill.PLACING;
		refillTimer = 0;
		openSince = -1;
		info("Out of TNT - taking some from a shulker.");
	}

	private void resetRefill() {
		stopWalking();
		refill = Refill.NONE;
		lastRefill = Refill.NONE;
		shulkerPos = null;
		refillOrigin = null;
	}

	private void walkTo(Vec3 target) {
		double dx = target.x - mc.player.getX();
		double dz = target.z - mc.player.getZ();
		if (dx * dx + dz * dz < 0.15) {
			stopWalking();
			return;
		}
		mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(mc.player.horizontalCollision || target.y - mc.player.getY() > 0.6);
	}

	private void stopWalking() {
		if (mc.options == null)
			return;
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
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

	/** Runs the shulker steps. Returns true while busy (normal placing pauses). */
	private boolean runRefill() {
		if (refill == Refill.NONE)
			return false;
		if (refill != lastRefill) {
			lastRefill = refill;
			stepAge = 0;
		}
		stepAge++;

		if (++refillTimer > 400) {
			info("Shulker restock timed out.");
			if (mc.player.containerMenu instanceof ShulkerBoxMenu)
				mc.player.closeContainer();
			resetRefill();
			return false;
		}

		switch (refill) {
			case PLACING -> {
				FindItemResult sh = InvUtils.find(this::isShulkerWithTnt);
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
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
						new BlockHitResult(new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5), Direction.UP,
								spot.below(), false));
				mc.player.swing(InteractionHand.MAIN_HAND);
				shulkerPos = spot;
				refill = Refill.WAIT_PLACED;
			}
			case WAIT_PLACED -> {
				if (mc.level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock)
					refill = Refill.OPENING;
				else if (stepAge > 12)
					refill = Refill.PLACING;
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
						return true;
					for (int i = 0; i < 27; i++) {
						if (menu.getSlot(i).getItem().is(Items.TNT))
							mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE,
									mc.player);
					}
					mc.player.closeContainer();
					refill = Refill.BREAKING;
				} else if (stepAge > 20)
					refill = Refill.OPENING;
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
				double bd = 64;
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
				if ((drop == null && pickupTimer < 90) || --pickupTimer <= 0) {
					stopWalking();
					refill = Refill.RETURNING;
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

	// ------------------------------------------------------------------ helpers

	private int freeHotbarSlot() {
		for (int i = 0; i < 9; i++) {
			if (mc.player.getInventory().getItem(i).isEmpty())
				return i;
		}
		return mc.player.getInventory().getSelectedSlot();
	}
}
