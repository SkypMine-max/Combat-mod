package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Controls (no modes, no keybinds):
 *  - Right click a block holding a PICKAXE  -> select it (turns green). Right click again -> deselect.
 *  - Right click holding an END CRYSTAL     -> start placing. Right click again -> stop.
 */
public class CustomCrystalAura extends Module {

	public enum PlaceMode {
		Selected,
		LookingAt,
		AnyObsidian
	}

	private final List<BlockPos> saved = new ArrayList<>();

	private final SettingGroup sgPlace = settings.createGroup("Place");
	private final SettingGroup sgBreak = settings.createGroup("Break");
	private final SettingGroup sgSafety = settings.createGroup("Safety");
	private final SettingGroup sgShield = settings.createGroup("Shield");
	private final SettingGroup sgRender = settings.createGroup("Render");

	// ---- place ----
	private final Setting<PlaceMode> placeMode = sgPlace.add(new EnumSetting.Builder<PlaceMode>()
			.name("place-mode")
			.description(
					"Selected = pickaxe + right click selects blocks, end crystal + right click starts. LookingAt = places on the obsidian you are looking at (just hold a crystal). AnyObsidian = any block from the list in range, end crystal + right click starts.")
			.defaultValue(PlaceMode.Selected).build());
	private final Setting<List<Block>> blocks = sgPlace.add(new BlockListSetting.Builder()
			.name("blocks").description("Blocks crystals may be placed on (AnyObsidian mode).")
			.defaultValue(Blocks.OBSIDIAN, Blocks.BEDROCK)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian).build());
	private final Setting<Boolean> alwaysBreak = sgBreak.add(new BoolSetting.Builder()
			.name("always-break")
			.description("Break crystals in range no matter what, even when placing hasn't been started.")
			.defaultValue(true).build());
	private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
			.name("place-range").description("Max distance to the selected block.")
			.defaultValue(5).min(1).max(100).sliderMax(100).build());
	private final Setting<Boolean> throughWalls = sgPlace.add(new BoolSetting.Builder()
			.name("through-walls").description("Place without needing line of sight (sends the place packet directly).")
			.defaultValue(true).build());
	private final Setting<Double> wallsRange = sgPlace.add(new DoubleSetting.Builder()
			.name("walls-range")
			.description(
					"Max range for placing AND breaking when through-walls is on (replaces place-range / break-range if higher).")
			.defaultValue(10).min(1).max(100).sliderMax(100).visible(throughWalls::get).build());
	private final Setting<Double> breakReach = sgPlace.add(new DoubleSetting.Builder()
			.name("server-break-reach")
			.description(
					"Only place crystals you can actually hit from where you stand. Set this to the farthest the server lets you break a crystal (test it, vanilla is about 4-6). 0 = off (places everything in range, fastest).")
			.defaultValue(0).min(0).max(100).sliderMax(100).build());
	private final Setting<Integer> maxBlocks = sgPlace.add(new IntSetting.Builder()
			.name("blocks-used").description("How many selected blocks get a crystal at once (1, 2, 3...).")
			.defaultValue(1).min(1).max(100).sliderMax(100).build());
	private final Setting<Boolean> aroundEnemy = sgPlace.add(new BoolSetting.Builder()
			.name("around-enemy").description("Use the selected blocks closest to the nearest enemy player first.")
			.defaultValue(true).build());
	private final Setting<Double> enemyRange = sgPlace.add(new DoubleSetting.Builder()
			.name("enemy-range").description("How far to look for an enemy.")
			.defaultValue(12).min(1).max(100).sliderMax(100).visible(aroundEnemy::get).build());
	private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
			.name("place-delay").description("Ticks between placements (0 = every tick).")
			.defaultValue(0).min(0).max(100).sliderMax(100).build());
	private final Setting<Boolean> autoRefill = sgPlace.add(new BoolSetting.Builder()
			.name("refill-from-inventory")
			.description("If there are no crystals in your hotbar, pull them from your inventory.")
			.defaultValue(true).build());
	private final Setting<Boolean> useShulkers = sgPlace.add(new BoolSetting.Builder()
			.name("refill-from-shulker")
			.description(
					"If you are out of crystals, place a shulker that has crystals, take them, and break it so it goes back to your inventory.")
			.defaultValue(true).build());
	private final Setting<Boolean> switchAfterObsidian = sgPlace.add(new BoolSetting.Builder()
			.name("switch-after-obsidian")
			.description(
					"LookingAt mode: the moment the obsidian you place appears, switch to an end crystal in your hotbar.")
			.defaultValue(true).visible(() -> placeMode.get() == PlaceMode.LookingAt).build());
	private final Setting<Boolean> switchWhenLooking = sgPlace.add(new BoolSetting.Builder()
			.name("switch-when-looking")
			.description(
					"LookingAt mode: switch to an end crystal in your hotbar as soon as you look at obsidian/bedrock (not while holding obsidian or mining).")
			.defaultValue(true).visible(() -> placeMode.get() == PlaceMode.LookingAt).build());
	private final Setting<Boolean> pauseWhileEating = sgPlace.add(new BoolSetting.Builder()
			.name("pause-while-eating")
			.description(
					"Stops placing/breaking/switching while you are using an item (eating a gap, drinking a potion), so you can heal in peace.")
			.defaultValue(true).build());
	private final Setting<Integer> resumeDelay = sgPlace.add(new IntSetting.Builder()
			.name("resume-delay")
			.description("Ticks to wait after you finish eating before it starts again (20 = 1 second).")
			.defaultValue(10).min(0).max(100).sliderMax(100).visible(pauseWhileEating::get).build());
	private final Setting<Boolean> pauseOnLowHealth = sgPlace.add(new BoolSetting.Builder()
			.name("pause-on-low-health")
			.description(
					"Pauses placing/breaking when your health gets low, and starts again once you have regenerated.")
			.defaultValue(true).build());
	private final Setting<Double> pauseHealth = sgPlace.add(new DoubleSetting.Builder()
			.name("pause-health")
			.description("Pause when your health (in half-hearts, 20 = full) drops to this or lower.")
			.defaultValue(8).min(1).max(36).sliderMax(20).visible(pauseOnLowHealth::get).build());
	private final Setting<Double> resumeHealth = sgPlace.add(new DoubleSetting.Builder()
			.name("resume-health")
			.description("Start again once your health (in half-hearts) is back up to this.")
			.defaultValue(16).min(1).max(36).sliderMax(20).visible(pauseOnLowHealth::get).build());
	private final Setting<Boolean> autoEatGap = sgPlace.add(new BoolSetting.Builder()
			.name("auto-eat-gap")
			.description(
					"While paused for low health, eat a golden apple from your hotbar/inventory (if you have one) to heal faster.")
			.defaultValue(true).visible(pauseOnLowHealth::get).build());
	private final Setting<Boolean> autoSwitch = sgPlace.add(new BoolSetting.Builder()
			.name("auto-switch").description("Switch to crystals in your hotbar.").defaultValue(true).build());

	// ---- break ----
	private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
			.name("break-range").description("Max distance to a crystal.")
			.defaultValue(5).min(1).max(100).sliderMax(100).build());
	private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
			.name("break-delay").description("Ticks between breaks (0 = every tick).")
			.defaultValue(0).min(0).max(100).sliderMax(100).build());
	private final Setting<Integer> breaksPerTick = sgBreak.add(new IntSetting.Builder()
			.name("breaks-per-tick").description("Crystals broken per tick.")
			.defaultValue(2).min(1).max(100).sliderMax(100).build());
	private final Setting<Boolean> sightCheck = sgBreak.add(new BoolSetting.Builder()
			.name("server-sight-check")
			.description(
					"Turn this on if the server won't let you hit crystals through walls. Then it only places crystals you can actually see (some part of the crystal in clear view) and only tries to break crystals you can see, so it stops placing ones it can't break.")
			.defaultValue(false).build());
	private final Setting<Boolean> idPredict = sgBreak.add(new BoolSetting.Builder()
			.name("id-predict")
			.description(
					"Breaks crystals the server hides from you (behind walls): right after placing, it attacks the entity IDs the new crystals will have. It guesses, so on a busy server it can hit something else - it skips IDs you can already see.")
			.defaultValue(false).build());
	private final Setting<Integer> idWindow = sgBreak.add(new IntSetting.Builder()
			.name("id-window")
			.description("Extra IDs to try past the expected ones, in case other entities spawned in between.")
			.defaultValue(2).min(0).max(6).sliderMax(6).visible(idPredict::get).build());

	// ---- safety: friends + enemy detection ----
	private final Setting<Boolean> friendFilter = sgSafety.add(new BoolSetting.Builder()
			.name("friend-filter")
			.description(
					"Friends (Meteor friends list) are never treated as enemies, and no crystal is placed where it would blow up a friend.")
			.defaultValue(true).build());
	private final Setting<Double> friendSafeRange = sgSafety.add(new DoubleSetting.Builder()
			.name("friend-safe-range")
			.description("Don't place a crystal if a friend is within this many blocks of it.")
			.defaultValue(6).min(1).max(20).sliderMax(15).visible(friendFilter::get).build());
	private final Setting<Boolean> friendProtectBreak = sgSafety.add(new BoolSetting.Builder()
			.name("friend-protect-break")
			.description(
					"Also don't break crystals that are within friend-safe-range of a friend. Careful: this can leave a crystal standing next to you too.")
			.defaultValue(false).visible(friendFilter::get).build());
	private final Setting<Boolean> onlyWhenEnemy = sgSafety.add(new BoolSetting.Builder()
			.name("when-enemy-detected")
			.description(
					"Paused until an enemy player (not a friend) comes within detect-range. Pauses placing, breaking and the shield. Resumes the moment one shows up.")
			.defaultValue(false).build());
	private final Setting<Double> detectRange = sgSafety.add(new DoubleSetting.Builder()
			.name("detect-range").description("How close a player must be to count as detected.")
			.defaultValue(20).min(1).max(200).sliderMax(100).visible(onlyWhenEnemy::get).build());

	private final Setting<Boolean> noSameLevel = sgSafety.add(new BoolSetting.Builder()
			.name("no-same-level")
			.description(
					"Never place a crystal at the same height as your feet (blasts at your own level hurt the most). Crystals go above or below you only.")
			.defaultValue(true).build());

	// ---- shield (AnyObsidian mode) ----
	private final Setting<Boolean> autoShield = sgShield.add(new BoolSetting.Builder()
			.name("obsidian-shield")
			.description(
					"AnyObsidian mode: builds a ring of obsidian around you so crystal explosions can't reach you. Crystals are never placed on the shield or inside it.")
			.defaultValue(true).visible(() -> placeMode.get() == PlaceMode.AnyObsidian).build());
	private final Setting<Integer> shieldDistance = sgShield.add(new IntSetting.Builder()
			.name("shield-distance")
			.description("How many blocks away from you the wall is (3 is the furthest you can reach to place).")
			.defaultValue(3).min(1).max(3).sliderMax(3)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoShield.get()).build());
	private final Setting<Integer> shieldHeight = sgShield.add(new IntSetting.Builder()
			.name("shield-height").description("How many blocks tall the wall is.")
			.defaultValue(3).min(1).max(5).sliderMax(5)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoShield.get()).build());
	private final Setting<Boolean> shieldRoof = sgShield.add(new BoolSetting.Builder()
			.name("shield-roof")
			.description("Also cover the top (stops crystals that hit you from above). Uses a lot of obsidian.")
			.defaultValue(false)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoShield.get()).build());
	private final Setting<Integer> shieldPerTick = sgShield.add(new IntSetting.Builder()
			.name("shield-blocks-per-tick").description("Obsidian blocks placed per tick while building the shield.")
			.defaultValue(2).min(1).max(8).sliderMax(8)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoShield.get()).build());
	private final Setting<Boolean> shieldRotate = sgShield.add(new BoolSetting.Builder()
			.name("shield-rotate")
			.description("Look at each block when placing it (turn on if the server rejects shield blocks).")
			.defaultValue(false)
			.visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoShield.get()).build());

	private final Setting<Boolean> autoBase = sgShield.add(new BoolSetting.Builder()
			.name("place-base-obsidian")
			.description(
					"AnyObsidian mode: when there is no obsidian a crystal can go on (the shield itself is off limits), place an obsidian block outside the shield to use as the base.")
			.defaultValue(true).visible(() -> placeMode.get() == PlaceMode.AnyObsidian).build());
	private final Setting<Boolean> baseFollowEnemy = sgShield.add(new BoolSetting.Builder()
			.name("base-follow-enemy")
			.description(
					"Put the base block as close to the nearest enemy as you can reach, and place a new one when the enemy moves away from the old ones. Off = base goes in front of you, just outside the shield.")
			.defaultValue(false).visible(() -> placeMode.get() == PlaceMode.AnyObsidian && autoBase.get()).build());

	// ---- render ----
	private final Setting<Boolean> highlight = sgRender.add(new BoolSetting.Builder()
			.name("highlight-selected").description("Show selected blocks in green (visible through walls).")
			.defaultValue(true).build());
	private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
			.name("side-color").defaultValue(new SettingColor(0, 255, 0, 60)).build());
	private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
			.name("line-color").defaultValue(new SettingColor(0, 255, 0, 255)).build());
	private final Setting<Boolean> targetEsp = sgRender.add(new BoolSetting.Builder()
			.name("target-esp")
			.description(
					"Draws a box around the enemy the aura is following (nearest non-friend player), through walls.")
			.defaultValue(true).build());
	private final Setting<Boolean> targetTracer = sgRender.add(new BoolSetting.Builder()
			.name("target-tracer").description("Draws a line from your crosshair to that enemy.")
			.defaultValue(true).build());
	private final Setting<Double> espRange = sgRender.add(new DoubleSetting.Builder()
			.name("esp-range").description("How far away an enemy still gets the ESP / tracer.")
			.defaultValue(60).min(1).max(300).sliderMax(150).build());
	private final Setting<SettingColor> espSideColor = sgRender.add(new ColorSetting.Builder()
			.name("esp-side-color").defaultValue(new SettingColor(255, 0, 0, 50)).build());
	private final Setting<SettingColor> espLineColor = sgRender.add(new ColorSetting.Builder()
			.name("esp-line-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());
	private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
			.name("tracer-color").defaultValue(new SettingColor(255, 60, 60, 220)).build());

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
	private BlockPos shulkerPos;
	private Vec3 refillOrigin; // where you stood when the refill started
	private int refillTimer, openSince, pickupTimer, stepAge;
	private Refill lastRefill = Refill.NONE;
	private BlockPos lastRemoved;

	private int placeTimer, breakTimer, eatPause;
	private boolean lowHealthPaused;
	private int lastEntityId, lastIdAge, placedSince;
	private Object idLevel;
	private boolean eatingGap, gapUsing;
	private int gapCooldown;
	private boolean wasRightDown;
	private BlockPos pendingObby;
	private int pendingAge;
	private int dragMode; // 0 = none, 1 = drag-select, 2 = drag-deselect
	private boolean running;
	private final Map<BlockPos, Integer> recentPlaces = new HashMap<>();
	private final Map<Integer, Integer> recentHits = new HashMap<>();
	private int age;
	private boolean waitingForEnemy;
	private int enemyHold;
	private final Set<BlockPos> shieldBlocks = new HashSet<>();
	private BlockPos shieldCenter;
	private boolean shieldNoObsidianWarned;
	private final Set<BlockPos> baseBlocks = new LinkedHashSet<>();
	private int baseCooldown, obiMovedTick = -1;
	private boolean noBaseAvailable;

	public CustomCrystalAura() {
		super(Addon.CATEGORY, "custom-crystal-aura",
				"Right click blocks with a pickaxe to select, right click with an end crystal to start.");
	}

	@Override
	public void onActivate() {
		running = false;
		switch (placeMode.get()) {
			case Selected ->
				info("Pickaxe + right click = select/deselect block. End crystal + right click = start/stop.");
			case LookingAt -> info("Hold an end crystal and look at obsidian/bedrock.");
			case AnyObsidian -> info("End crystal + right click = start/stop.");
		}
	}

	@Override
	public void onDeactivate() {
		recentPlaces.clear();
		recentHits.clear();
		running = false;
		lowHealthPaused = false;
		lastEntityId = 0;
		placedSince = 0;
		stopGapEating();
		refill = Refill.NONE;
		shulkerPos = null;
		pendingObby = null;
		waitingForEnemy = false;
		enemyHold = 0;
		shieldBlocks.clear();
		shieldCenter = null;
		shieldNoObsidianWarned = false;
		baseBlocks.clear();
		baseCooldown = 0;
		noBaseAvailable = false;
		if (mc.options != null)
			stopWalking();
	}

	/** Tracks the newest entity ID the server has told us about (used by id-predict). */
	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (event.packet instanceof ClientboundAddEntityPacket p) {
			if (p.getId() > lastEntityId || age - lastIdAge > 200) {
				lastEntityId = p.getId();
				placedSince = 0; // the real counter is now known again
			}
			lastIdAge = age;
		}
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null)
			return;
		age++;

		if (idLevel != mc.level) { // new world / server: old IDs mean nothing
			idLevel = mc.level;
			lastEntityId = 0;
			placedSince = 0;
		}

		// low health: stop until you have regenerated (hysteresis so it doesn't flicker)
		if (pauseOnLowHealth.get()) {
			float hp = mc.player.getHealth();
			if (!lowHealthPaused && hp <= pauseHealth.get()) {
				lowHealthPaused = true;
				info("Paused - low health. Resumes at " + resumeHealth.get() + ".");
			} else if (lowHealthPaused && hp >= Math.max(resumeHealth.get(), pauseHealth.get() + 1)) {
				lowHealthPaused = false;
				info("Health recovered - resuming.");
			}
		} else
			lowHealthPaused = false;
		if (gapCooldown > 0)
			gapCooldown--;
		if (autoEatGap.get() && handleGapEating())
			return;

		// eating / drinking: hands off until you're done (and a short moment after)
		if (pauseWhileEating.get()) {
			if (mc.player.isUsingItem())
				eatPause = resumeDelay.get();
			else if (eatPause > 0)
				eatPause--;
			if (mc.player.isUsingItem() || eatPause > 0) {
				wasRightDown = mc.options.keyUse.isDown();
				return;
			}
		} else
			eatPause = 0;

		boolean rightDown = mc.options.keyUse.isDown();
		if (rightDown && mc.screen == null) {
			if (!wasRightDown)
				onRightClick();
			else
				onRightHeld();
		}
		if (!rightDown) {
			lastRemoved = null;
			dragMode = 0;
		}
		wasRightDown = rightDown;

		recentPlaces.values().removeIf(t -> age - t > 1);
		recentHits.values().removeIf(t -> age - t > 3);
		if (placeTimer > 0)
			placeTimer--;
		if (breakTimer > 0)
			breakTimer--;

		// when-enemy-detected: sit idle until a non-friend player is in range (short grace so it doesn't flicker)
		if (onlyWhenEnemy.get()) {
			if (nearestEnemy(detectRange.get()) != null)
				enemyHold = 20;
			else if (enemyHold > 0)
				enemyHold--;
			boolean wait = enemyHold <= 0;
			if (wait && !waitingForEnemy)
				info("No enemy nearby - paused until one is detected.");
			else if (!wait && waitingForEnemy)
				info("Enemy detected - resuming.");
			waitingForEnemy = wait;
		} else
			waitingForEnemy = false;

		if (lowHealthPaused || (waitingForEnemy && refill == Refill.NONE))
			return;

		checkObsidianSwitch(rightDown);

		boolean placing = placingActive();
		boolean busy = runRefill();
		if (!busy && (placing || alwaysBreak.get()) && breakTimer == 0)
			doBreak();
		boolean shielding = !busy && placing && runShield();
		if (!busy && placing && !shielding && placeTimer == 0) {
			doPlace();
			if (noBaseAvailable)
				placeBase();
		}
	}

	/** LookingAt mode: switch to a crystal when obsidian is placed, and when you look at obsidian/bedrock. */
	private void checkObsidianSwitch(boolean rightDown) {
		if (placeMode.get() != PlaceMode.LookingAt || mc.screen != null) {
			pendingObby = null;
			return;
		}

		// 1) obsidian you are placing: switch the moment it appears
		if (switchAfterObsidian.get()) {
			if (pendingObby != null) {
				if (mc.level.getBlockState(pendingObby).is(Blocks.OBSIDIAN)) {
					switchToCrystal();
					pendingObby = null;
					return;
				}
				if (++pendingAge > 10)
					pendingObby = null; // placement never happened
			}

			if (rightDown && mc.player.getMainHandItem().is(Items.OBSIDIAN)
					&& mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
				BlockPos clicked = bhr.getBlockPos();
				pendingObby = (mc.level.getBlockState(clicked).canBeReplaced()
						? clicked
						: clicked.relative(bhr.getDirection())).immutable();
				pendingAge = 0;
			}
		} else
			pendingObby = null;

		// 2) simply looking at obsidian/bedrock: switch (but never while placing obsidian or mining)
		if (switchWhenLooking.get()
				&& !mc.player.getMainHandItem().is(Items.OBSIDIAN)
				&& !mc.options.keyAttack.isDown()
				&& mc.hitResult instanceof BlockHitResult look && look.getType() == HitResult.Type.BLOCK
				&& isCrystalBase(look.getBlockPos())) {
			switchToCrystal();
		}
	}

	/** While paused for low health: eat a golden apple. Returns true while it is busy eating. */
	private boolean handleGapEating() {
		if (!lowHealthPaused || mc.screen != null) {
			stopGapEating();
			return false;
		}
		if (mc.player.isUsingItem()) {
			if (eatingGap) {
				gapUsing = true;
				mc.options.keyUse.setDown(true); // keep holding right click until the gap is eaten
				return true;
			}
			return false; // eating something else (potion etc.) - leave it alone
		}
		if (gapUsing) { // just finished one
			stopGapEating();
			gapCooldown = 100; // give the regeneration time before eating another
			return false;
		}
		if (gapCooldown > 0) {
			stopGapEating();
			return false;
		}

		FindItemResult gap = InvUtils.find(Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE);
		if (!gap.found() || gap.isOffhand()) {
			stopGapEating();
			return false;
		}
		if (!gap.isHotbar()) {
			InvUtils.move().from(gap.slot()).toHotbar(freeHotbarSlot());
			return true;
		}
		InvUtils.swap(gap.slot(), false);
		eatingGap = true;
		mc.options.keyUse.setDown(true);
		return true;
	}

	private void stopGapEating() {
		if (eatingGap && mc.options != null)
			mc.options.keyUse.setDown(false);
		eatingGap = false;
		gapUsing = false;
	}

	private void switchToCrystal() {
		if (holdingCrystal())
			return;
		FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
		if (crystal.found())
			InvUtils.swap(crystal.slot(), false);
	}

	private void onRightClick() {
		var main = mc.player.getMainHandItem();

		// PICKAXE + right click on a block = select / deselect
		if (placeMode.get() == PlaceMode.Selected && main.is(ItemTags.PICKAXES)) {
			if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK)
				return;
			BlockPos p = bhr.getBlockPos().immutable();
			if (saved.remove(p)) {
				lastRemoved = p;
				dragMode = 2;
				info("Deselected " + p.toShortString());
				return;
			}
			dragMode = 1;
			if (!isCrystalBase(p)) {
				info("That block isn't obsidian or bedrock.");
				return;
			}
			saved.add(p);
			info("Selected " + p.toShortString() + " (" + saved.size() + " total).");
			return;
		}

		// END CRYSTAL + right click anywhere = start / stop
		boolean crystal = main.is(Items.END_CRYSTAL)
				|| (main.isEmpty() && mc.player.getOffhandItem().is(Items.END_CRYSTAL));
		if (crystal && placeMode.get() != PlaceMode.LookingAt) {
			running = !running;
			info(running ? "Crystal aura STARTED." : "Crystal aura STOPPED.");
		}
	}

	/** Holding right click with a pickaxe: selects what you look at, or deselects if your first click was a deselect. */
	private void onRightHeld() {
		if (placeMode.get() != PlaceMode.Selected || !mc.player.getMainHandItem().is(ItemTags.PICKAXES))
			return;
		if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK)
			return;
		BlockPos p = bhr.getBlockPos().immutable();
		if (dragMode == 2) {
			if (saved.remove(p))
				info("Deselected " + p.toShortString() + " (" + saved.size() + " left).");
			return;
		}
		if (p.equals(lastRemoved) || saved.contains(p) || !isCrystalBase(p))
			return;
		saved.add(p);
		info("Selected " + p.toShortString() + " (" + saved.size() + " total).");
	}

	// ================= crystal refill (inventory + shulker) =================

	private int freeHotbarSlot() {
		for (int i = 0; i < 9; i++)
			if (mc.player.getInventory().getItem(i).isEmpty())
				return i;
		return mc.player.getInventory().getSelectedSlot();
	}

	private boolean isShulkerWithCrystals(net.minecraft.world.item.ItemStack st) {
		if (!(Block.byItem(st.getItem()) instanceof ShulkerBoxBlock))
			return false;
		var contents = st.get(DataComponents.CONTAINER);
		if (contents == null)
			return false;

		// 1) read the stored items directly (their toString names the item)
		try {
			for (var t : contents.nonEmptyItems()) {
				if (String.valueOf(t).contains("end_crystal"))
					return true;
			}
		} catch (Throwable ignored) {
		}

		// 2) fallback: serialize the whole stack and look for the crystal id
		try {
			return net.minecraft.world.item.ItemStack.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, st)
					.result().map(tag -> tag.toString().contains("end_crystal")).orElse(false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void startShulkerRefill() {
		if (refill != Refill.NONE)
			return;
		if (!InvUtils.find(this::isShulkerWithCrystals).found())
			return;
		refillOrigin = mc.player.position();
		refill = Refill.PLACING;
		refillTimer = 0;
		openSince = -1;
		info("Out of crystals - taking some from a shulker.");
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
		} // basically on top of it
		mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(mc.player.horizontalCollision || target.y - mc.player.getY() > 0.6);
	}

	private void stopWalking() {
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

	/** Runs the shulker refill steps. Returns true while it is busy (placing/breaking pauses). */
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
				FindItemResult sh = InvUtils.find(this::isShulkerWithCrystals);
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
						if (menu.getSlot(i).getItem().is(Items.END_CRYSTAL))
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
					stopWalking();
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

	@EventHandler
	private void onRender(Render3DEvent event) {
		renderTarget(event);
		if (!highlight.get() || placeMode.get() != PlaceMode.Selected)
			return;
		for (BlockPos p : saved) {
			event.renderer.box(p, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
		}
	}

	private void doBreak() {
		double r = throughWalls.get() ? Math.max(breakRange.get(), wallsRange.get()) : breakRange.get();
		List<EndCrystal> crystals = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof EndCrystal c && c.isAlive() && boxDist(mc.player.getEyePosition(), c.getBoundingBox()) <= r
					&& !recentHits.containsKey(c.getId())
					&& !(friendProtectBreak.get() && friendNear(c.position()))
					&& (throughWalls.get() || mc.player.hasLineOfSight(c))
					&& (!sightCheck.get() || boxVisible(mc.player.getEyePosition(), c.getBoundingBox())))
				crystals.add(c);
		}
		if (crystals.isEmpty())
			return;
		crystals.sort(Comparator.comparingDouble(c -> c.distanceToSqr(mc.player)));

		int n = Math.min(breaksPerTick.get(), crystals.size());
		for (int i = 0; i < n; i++) {
			EndCrystal c = crystals.get(i);
			mc.gameMode.attack(mc.player, c);
			recentHits.put(c.getId(), age);
		}
		mc.player.swing(InteractionHand.MAIN_HAND);
		breakTimer = breakDelay.get();
	}

	private void doPlace() {
		noBaseAvailable = false;
		FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
		InteractionHand hand = mc.player.getOffhandItem().is(Items.END_CRYSTAL) ? InteractionHand.OFF_HAND : null;
		if (hand == null && !crystal.found()) {
			// none in hotbar: pull from inventory, else from a shulker
			FindItemResult inv = InvUtils.find(Items.END_CRYSTAL);
			if (autoRefill.get() && inv.found() && !inv.isHotbar() && !inv.isOffhand()) {
				InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot());
			} else if (useShulkers.get() && !inv.found()) {
				startShulkerRefill();
			}
			return;
		}

		Vec3 anchor = mc.player.position();
		if (aroundEnemy.get()) {
			Player enemy = nearestEnemy(enemyRange.get());
			if (enemy != null)
				anchor = enemy.position();
		}
		final Vec3 center = anchor;

		double r = throughWalls.get() ? Math.max(placeRange.get(), wallsRange.get()) : placeRange.get();
		Vec3 eye = mc.player.getEyePosition();
		List<BlockPos> source = new ArrayList<>();
		switch (placeMode.get()) {
			case Selected -> source.addAll(saved);
			case LookingAt -> {
				if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
					source.add(bhr.getBlockPos().immutable());
			}
			case AnyObsidian -> {
				BlockPos base = mc.player.blockPosition();
				int ri = (int) Math.ceil(Math.min(r, 20)); // scan cube capped so big ranges do not freeze the game
				for (BlockPos q : BlockPos.betweenClosed(base.offset(-ri, -ri, -ri), base.offset(ri, ri, ri))) {
					if (blocks.get().contains(mc.level.getBlockState(q).getBlock()) && !shieldBlocks.contains(q))
						source.add(q.immutable());
				}
			}
		}
		if (source.isEmpty()) {
			noBaseAvailable = true;
			return;
		}

		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos p : source) {
			if (!isCrystalBase(p))
				continue;
			if (p.getCenter().distanceTo(eye) > r)
				continue;
			if (recentPlaces.containsKey(p))
				continue;
			if (!canPlaceOn(p))
				continue;
			if (shieldBlocks.contains(p) || insideShield(p))
				continue; // never put a crystal on / inside our own shield
			if (noSameLevel.get() && p.getY() + 1 == mc.player.blockPosition().getY())
				continue; // crystal would sit at our feet level
			if (breakReach.get() > 0 && boxDist(eye, crystalBox(p)) > breakReach.get())
				continue;
			if (!throughWalls.get() && !visible(eye, p))
				continue;
			if (sightCheck.get() && !boxVisible(eye, crystalBox(p)))
				continue;
			if (friendNear(new Vec3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5)))
				continue; // would blow up a friend
			candidates.add(p);
		}
		if (candidates.isEmpty()) {
			noBaseAvailable = true;
			return;
		}
		candidates.sort(Comparator.comparingDouble(p -> p.getCenter().distanceToSqr(center)));

		if (hand == null) {
			boolean holding = mc.player.getMainHandItem().is(Items.END_CRYSTAL);
			if (!holding) {
				if (!autoSwitch.get())
					return;
				InvUtils.swap(crystal.slot(), false);
			}
			hand = InteractionHand.MAIN_HAND;
		}

		int n = Math.min(maxBlocks.get(), candidates.size());
		for (int i = 0; i < n; i++) {
			BlockPos p = candidates.get(i);
			Vec3 hitPos = new Vec3(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
			// Hand-built hit result: no client raycast, so walls in between don't matter client-side.
			mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hitPos, Direction.UP, p, false));
			recentPlaces.put(p, age);
		}
		mc.player.swing(hand);
		placeTimer = placeDelay.get();
		if (idPredict.get())
			predictBreaks(n);
	}

	/** Attacks the IDs the crystals we just placed should have, even if the server never shows them to us. */
	private void predictBreaks(int placed) {
		if (lastEntityId <= 0 || mc.getConnection() == null)
			return;
		if (placedSince > 40)
			placedSince = 0; // placements must have failed, stop drifting
		int base = lastEntityId + placedSince;
		for (int id = base + 1; id <= base + placed + idWindow.get(); id++) {
			if (mc.level.getEntity(id) != null)
				continue; // a real entity we can see - never attack it by guess
			EndCrystal fake = new EndCrystal(mc.level, 0, 0, 0);
			fake.setId(id);
			mc.getConnection().send(new ServerboundInteractPacket(
					fake.getId(),
					InteractionHand.MAIN_HAND,
					Vec3.ZERO,
					mc.player.isShiftKeyDown()));
		}
		placedSince += placed;
	}

	/** Distance from a point to the closest point of a box (0 if inside) - how the server measures entity reach. */
	private static double boxDist(Vec3 p, AABB b) {
		double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
		double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
		double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Where the crystal's hitbox will be once placed on top of block p. */
	private static AABB crystalBox(BlockPos p) {
		return new AABB(p.getX() - 0.5, p.getY() + 1, p.getZ() - 0.5, p.getX() + 1.5, p.getY() + 3, p.getZ() + 1.5);
	}

	private boolean holdingCrystal() {
		return mc.player.getMainHandItem().is(Items.END_CRYSTAL) || mc.player.getOffhandItem().is(Items.END_CRYSTAL);
	}

	private boolean placingActive() {
		return placeMode.get() == PlaceMode.LookingAt ? holdingCrystal() : running;
	}

	private boolean isCrystalBase(BlockPos p) {
		Block b = mc.level.getBlockState(p).getBlock();
		return b == Blocks.OBSIDIAN || b == Blocks.BEDROCK;
	}

	private boolean canPlaceOn(BlockPos p) {
		BlockPos a = p.above(), b = p.above(2);
		if (!mc.level.getBlockState(a).isAir() || !mc.level.getBlockState(b).isAir())
			return false;
		AABB box = new AABB(a.getX(), a.getY(), a.getZ(), a.getX() + 1, a.getY() + 2, a.getZ() + 1);
		return mc.level.getEntities((Entity) null, box).stream()
				.noneMatch(e -> !e.isSpectator() && !(e instanceof EndCrystal c && recentHits.containsKey(c.getId())));
	}

	/** True if at least one point of the box (centre or a corner) can be reached from the eye without a block in between. */
	private boolean boxVisible(Vec3 eye, AABB b) {
		double e = 0.05;
		double[] xs = { b.minX + e, (b.minX + b.maxX) / 2, b.maxX - e };
		double[] ys = { b.minY + e, (b.minY + b.maxY) / 2, b.maxY - e };
		double[] zs = { b.minZ + e, (b.minZ + b.maxZ) / 2, b.maxZ - e };
		for (double x : xs) {
			for (double y : ys) {
				for (double z : zs) {
					Vec3 pt = new Vec3(x, y, z);
					var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, pt,
							net.minecraft.world.level.ClipContext.Block.COLLIDER,
							net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
					if (hit.getType() == HitResult.Type.MISS)
						return true;
				}
			}
		}
		return false;
	}

	private boolean visible(Vec3 eye, BlockPos p) {
		var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, p.getCenter().add(0, 0.5, 0),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
				mc.player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p);
	}

	private Player nearestEnemy(double range) {
		Player best = null;
		double bd = range * range;
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isAlive() || p.isSpectator())
				continue;
			if (friendFilter.get() && Friends.get().isFriend(p))
				continue;
			double d = p.distanceToSqr(mc.player);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}

	/** True if a friend stands within friend-safe-range of this point (only when friend-filter is on). */
	private boolean friendNear(Vec3 pos) {
		if (!friendFilter.get())
			return false;
		double r2 = friendSafeRange.get() * friendSafeRange.get();
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isAlive())
				continue;
			if (Friends.get().isFriend(p) && p.distanceToSqr(pos) <= r2)
				return true;
		}
		return false;
	}

	/** ESP box + tracer on the enemy the aura is following (nearest non-friend player). */
	private void renderTarget(Render3DEvent event) {
		if ((!targetEsp.get() && !targetTracer.get()) || mc.player == null || mc.level == null)
			return;
		Player t = nearestEnemy(espRange.get());
		if (t == null)
			return;
		Vec3 pos = t.getPosition(event.tickDelta);
		double w = t.getBbWidth() / 2.0, h = t.getBbHeight();
		if (targetEsp.get())
			event.renderer.box(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + h, pos.z + w, espSideColor.get(),
					espLineColor.get(), ShapeMode.Both, 0);
		if (targetTracer.get()) {
			Vec3 from = mc.player.getEyePosition(event.tickDelta)
					.add(mc.player.getViewVector(event.tickDelta).scale(1.0));
			event.renderer.line(from.x, from.y, from.z, pos.x, pos.y + h / 2.0, pos.z, tracerColor.get());
		}
	}

	// ================= obsidian shield (AnyObsidian mode) =================

	private boolean shieldOn() {
		return autoShield.get() && placeMode.get() == PlaceMode.AnyObsidian;
	}

	/**
	 * Is this crystal base part of the shield area? That is the ring itself, the blocks under it, everything inside it
	 * and the roof layer - a crystal anywhere there would hurt us.
	 */
	private boolean insideShield(BlockPos p) {
		if (!shieldOn() || shieldCenter == null)
			return false;
		int d = shieldDistance.get();
		int top = shieldHeight.get() + (shieldRoof.get() ? 1 : 0);
		int dy = p.getY() - shieldCenter.getY();
		return Math.abs(p.getX() - shieldCenter.getX()) <= d && Math.abs(p.getZ() - shieldCenter.getZ()) <= d
				&& dy >= -1 && dy <= top;
	}

	/** Wall positions around c, lowest layer first (so every block has something to be placed against). */
	private List<BlockPos> shieldTargets(BlockPos c) {
		int d = shieldDistance.get(), h = shieldHeight.get();
		List<BlockPos> out = new ArrayList<>();
		for (int y = 0; y < h; y++)
			for (int x = -d; x <= d; x++)
				for (int z = -d; z <= d; z++)
					if (Math.max(Math.abs(x), Math.abs(z)) == d)
						out.add(c.offset(x, y, z));
		if (shieldRoof.get())
			for (int x = -d; x <= d; x++)
				for (int z = -d; z <= d; z++)
					out.add(c.offset(x, h, z));
		return out;
	}

	/** Builds the obsidian ring. Returns true if it placed something this tick (crystal placing waits for it). */
	private boolean runShield() {
		if (!shieldOn())
			return false;

		// (re)centre on you; if you walk off, a new ring is built where you are
		BlockPos feet = mc.player.blockPosition();
		int d = shieldDistance.get();
		if (shieldCenter == null
				|| Math.max(Math.abs(feet.getX() - shieldCenter.getX()),
						Math.abs(feet.getZ() - shieldCenter.getZ())) >= Math.max(1, d - 1)
				|| Math.abs(feet.getY() - shieldCenter.getY()) > 1) {
			shieldCenter = feet.immutable();
			shieldNoObsidianWarned = false;
		}
		if (shieldBlocks.size() > 5000)
			shieldBlocks.clear();

		List<BlockPos> targets = shieldTargets(shieldCenter);
		shieldBlocks.addAll(targets);

		Vec3 eye = mc.player.getEyePosition();
		List<BlockPos> missing = new ArrayList<>();
		for (BlockPos p : targets) {
			if (mc.level.getBlockState(p).canBeReplaced() && p.getCenter().distanceTo(eye) <= 4.5)
				missing.add(p);
		}
		if (missing.isEmpty())
			return false;

		FindItemResult obi = obsidianInHotbar();
		if (obi == null)
			return obiMovedTick == age; // true = we just pulled some into the hotbar, wait a tick

		int placed = 0;
		for (BlockPos p : missing) {
			if (placed >= shieldPerTick.get())
				break;
			if (BlockUtils.place(p, obi, shieldRotate.get(), 50, true, true, true))
				placed++;
		}
		return placed > 0;
	}

	/** Obsidian in the hotbar (pulled from the inventory if needed). Null = not ready: just moved it, or you have none. */
	private FindItemResult obsidianInHotbar() {
		FindItemResult obi = InvUtils.findInHotbar(Items.OBSIDIAN);
		if (obi.found())
			return obi;
		FindItemResult inv = InvUtils.find(Items.OBSIDIAN);
		if (inv.found() && autoRefill.get() && !inv.isHotbar() && !inv.isOffhand()) {
			InvUtils.move().from(inv.slot()).toHotbar(freeHotbarSlot());
			obiMovedTick = age;
			return null;
		}
		if (!shieldNoObsidianWarned) {
			shieldNoObsidianWarned = true;
			info("No obsidian in your inventory (shield / base).");
		}
		return null;
	}

	/** Outside the shield (or at least 2 blocks from you when there is no shield)? */
	private boolean outsideShield(BlockPos b) {
		if (shieldBlocks.contains(b))
			return false;
		BlockPos c = (shieldOn() && shieldCenter != null) ? shieldCenter : mc.player.blockPosition();
		int need = (shieldOn() && shieldCenter != null) ? shieldDistance.get() + 1 : 2;
		return Math.max(Math.abs(b.getX() - c.getX()), Math.abs(b.getZ() - c.getZ())) >= need;
	}

	/**
	 * Places one obsidian block outside the shield for crystals to stand on. Only called when doPlace found nothing
	 * to place on. With base-follow-enemy it picks the reachable spot closest to the enemy.
	 */
	private void placeBase() {
		if (!autoBase.get() || placeMode.get() != PlaceMode.AnyObsidian)
			return;
		if (baseCooldown > 0) {
			baseCooldown--;
			return;
		}

		Vec3 eye = mc.player.getEyePosition();
		Player enemy = baseFollowEnemy.get() ? nearestEnemy(enemyRange.get()) : null;

		// already have a base we placed? (the crystal on it just hasn't been broken yet, so don't spam obsidian)
		baseBlocks.removeIf(b -> !isCrystalBase(b));
		for (BlockPos b : baseBlocks) {
			if (b.getCenter().distanceTo(eye) > 5.5)
				continue; // out of reach, doesn't count
			if (enemy != null && enemy.position().distanceTo(b.getCenter()) > 4.0)
				continue; // enemy walked away from it - make a new one near them
			return;
		}

		Vec3 target;
		if (enemy != null)
			target = enemy.position();
		else {
			Vec3 look = mc.player.getLookAngle();
			target = mc.player.position().add(new Vec3(look.x, 0, look.z).normalize().scale(shieldDistance.get() + 2));
		}

		BlockPos origin = mc.player.blockPosition();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos b = origin.offset(dx, dy, dz);
					if (!outsideShield(b) || !BlockUtils.canPlace(b, true))
						continue;
					if (noSameLevel.get() && b.getY() + 1 == origin.getY())
						continue; // crystal on it would be at our feet level
					BlockPos below = b.below();
					if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP))
						continue;
					if (!mc.level.getBlockState(b.above()).isAir() || !mc.level.getBlockState(b.above(2)).isAir())
						continue; // the crystal needs 2 free blocks above
					Vec3 c = b.getCenter();
					if (c.distanceTo(eye) > 4.5)
						continue;
					if (breakReach.get() > 0 && boxDist(eye, crystalBox(b)) > breakReach.get())
						continue;
					if (friendNear(new Vec3(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5)))
						continue;
					double d2 = c.distanceToSqr(target);
					if (d2 < bd) {
						bd = d2;
						best = b;
					}
				}
			}
		}
		if (best == null) {
			baseCooldown = 5;
			return;
		}

		FindItemResult obi = obsidianInHotbar();
		if (obi == null)
			return;
		if (BlockUtils.place(best, obi, shieldRotate.get(), 50, true, true, true)) {
			baseBlocks.add(best.immutable());
			if (baseBlocks.size() > 8)
				baseBlocks.remove(baseBlocks.iterator().next()); // just stop tracking the oldest, the block stays
			baseCooldown = 2;
		} else
			baseCooldown = 5;
	}
}
