package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CustomTriggerBot extends Module {

	private final SettingGroup sg = settings.getDefaultGroup();

	private final Setting<Set<EntityType<?>>> entities = sg.add(new EntityTypeListSetting.Builder()
			.name("entities").description("Entities to attack.")
			.defaultValue(EntityType.PLAYER).onlyAttackable().build());
	private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
			.name("range").description("How far the crosshair ray reaches.")
			.defaultValue(3.0).min(1).sliderMax(6).build());
	private final Setting<Boolean> respectCooldown = sg.add(new BoolSetting.Builder()
			.name("respect-cooldown").description("Wait for the full weapon cooldown (max damage).")
			.defaultValue(true).build());
	private final Setting<Integer> minDelay = sg.add(new IntSetting.Builder()
			.name("min-delay").description("Min ticks between hits.")
			.defaultValue(0).min(0).sliderMax(20).build());
	private final Setting<Integer> maxDelay = sg.add(new IntSetting.Builder()
			.name("max-delay").description("Max ticks between hits (random between min and max).")
			.defaultValue(2).min(0).sliderMax(20).build());
	private final Setting<CritUtil.CritMode> crits = sg.add(new EnumSetting.Builder<CritUtil.CritMode>()
			.name("criticals").description("Packet = 100% critical hits.")
			.defaultValue(CritUtil.CritMode.Packet).build());

	private final Random random = new Random();
	private int timer;

	public CustomTriggerBot() {
		super(Addon.CATEGORY, "custom-trigger-bot", "Attacks only what your crosshair is on.");
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null)
			return;
		if (timer > 0) {
			timer--;
			return;
		}

		Entity target = lookedAt();
		if (target == null)
			return;
		if (respectCooldown.get() && mc.player.getAttackStrengthScale(0.5f) < 1f)
			return;
		if (!CritUtil.canHit(crits.get()))
			return;

		CritUtil.apply(crits.get());
		mc.gameMode.attack(mc.player, target);
		mc.player.swing(InteractionHand.MAIN_HAND);

		int lo = Math.min(minDelay.get(), maxDelay.get()), hi = Math.max(minDelay.get(), maxDelay.get());
		timer = lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);
	}

	private Entity lookedAt() {
		double r = range.get();
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1f).scale(r);
		AABB box = mc.player.getBoundingBox().expandTowards(look).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, eye, eye.add(look), box,
				e -> !e.isSpectator() && e.isPickable() && entities.get().contains(e.getType()), r * r);
		return hit == null ? null : hit.getEntity();
	}
}
