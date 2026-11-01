package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CustomKillAura extends Module {

	public enum Sort {
		Closest,
		LowestHealth
	}

	private final SettingGroup sgTargets = settings.createGroup("Targets");
	private final SettingGroup sg = settings.getDefaultGroup();

	// ---- who to hit ----
	private final Setting<Boolean> players = sgTargets.add(new BoolSetting.Builder()
			.name("players").description("Attack players.").defaultValue(true).build());
	private final Setting<Boolean> hostile = sgTargets.add(new BoolSetting.Builder()
			.name("hostile-mobs").description("Attack hostile mobs (zombies, creepers, skeletons...).")
			.defaultValue(true).build());
	private final Setting<Boolean> animals = sgTargets.add(new BoolSetting.Builder()
			.name("animals").description("Attack animals (cows, pigs, sheep, fish, bats...).").defaultValue(true)
			.build());
	private final Setting<Boolean> others = sgTargets.add(new BoolSetting.Builder()
			.name("other-mobs").description("Attack villagers, golems and other non-hostile mobs.").defaultValue(true)
			.build());
	private final Setting<Boolean> ignorePets = sgTargets.add(new BoolSetting.Builder()
			.name("ignore-tamed-pets").description("Never hit tamed wolves, cats, parrots.").defaultValue(true)
			.build());
	private final Setting<Boolean> ignoreFriends = sgTargets.add(new BoolSetting.Builder()
			.name("ignore-friends").defaultValue(true).build());
	private final Setting<Set<EntityType<?>>> ignored = sgTargets.add(new EntityTypeListSetting.Builder()
			.name("ignored-entities").description("Specific entity types to NEVER attack (filter list).")
			.onlyAttackable().build());

	// ---- how ----
	private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
			.name("range").description("Attack range.").defaultValue(4.0).min(1).sliderMax(8).build());
	private final Setting<Boolean> walls = sg.add(new BoolSetting.Builder()
			.name("through-walls").description("Attack targets you can't see.").defaultValue(false).build());
	private final Setting<Integer> maxTargets = sg.add(new IntSetting.Builder()
			.name("max-targets").description("How many entities to hit per attack.")
			.defaultValue(3).min(1).sliderMax(10).build());
	private final Setting<Sort> sort = sg.add(new EnumSetting.Builder<Sort>()
			.name("sort").defaultValue(Sort.Closest).build());
	private final Setting<Boolean> respectCooldown = sg.add(new BoolSetting.Builder()
			.name("respect-cooldown").description("Wait for full weapon cooldown.").defaultValue(true).build());
	private final Setting<Integer> minDelay = sg.add(new IntSetting.Builder()
			.name("min-delay").description("Min ticks between attacks.").defaultValue(0).min(0).sliderMax(20).build());
	private final Setting<Integer> maxDelay = sg.add(new IntSetting.Builder()
			.name("max-delay").description("Max ticks between attacks.").defaultValue(2).min(0).sliderMax(20).build());
	private final Setting<CritUtil.CritMode> crits = sg.add(new EnumSetting.Builder<CritUtil.CritMode>()
			.name("criticals").description("Packet = 100% critical hits.")
			.defaultValue(CritUtil.CritMode.Packet).build());

	private final Random random = new Random();
	private int timer;

	public CustomKillAura() {
		super(Addon.CATEGORY, "custom-kill-aura", "Hits everything around you.");
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null)
			return;
		if (timer > 0) {
			timer--;
			return;
		}
		if (respectCooldown.get() && mc.player.getAttackStrengthScale(0.5f) < 1f)
			return;
		if (!CritUtil.canHit(crits.get()))
			return;

		double r = range.get();
		Comparator<Entity> cmp = sort.get() == Sort.Closest
				? Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))
				: Comparator.comparingDouble(e -> ((LivingEntity) e).getHealth());

		List<Entity> targets = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!shouldAttack(e))
				continue;
			if (e.distanceTo(mc.player) > r)
				continue;
			if (!walls.get() && !mc.player.hasLineOfSight(e))
				continue;
			targets.add(e);
		}
		if (targets.isEmpty())
			return;
		targets.sort(cmp);

		CritUtil.apply(crits.get()); // one crit "hop" covers every hit this tick
		int n = Math.min(maxTargets.get(), targets.size());
		for (int i = 0; i < n; i++)
			mc.gameMode.attack(mc.player, targets.get(i));
		mc.player.swing(InteractionHand.MAIN_HAND);

		int lo = Math.min(minDelay.get(), maxDelay.get()), hi = Math.max(minDelay.get(), maxDelay.get());
		timer = lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);
	}

	private boolean shouldAttack(Entity e) {
		if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive())
			return false;
		EntityType<?> type = e.getType();
		if (type == EntityType.ARMOR_STAND || ignored.get().contains(type))
			return false;

		if (e instanceof Player p) {
			if (!players.get() || p.isSpectator())
				return false;
			return !(ignoreFriends.get() && Friends.get().isFriend(p));
		}
		if (ignorePets.get() && e instanceof TamableAnimal pet && pet.isTame())
			return false;

		MobCategory cat = type.getCategory();
		if (cat == MobCategory.MONSTER)
			return hostile.get();
		if (cat == MobCategory.MISC)
			return others.get();
		return animals.get();
	}
}
