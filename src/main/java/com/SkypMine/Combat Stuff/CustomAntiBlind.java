package com.kkllffaa.meteor_litematica_printer;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.effect.MobEffects;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CustomAntiBlind extends Module {

	private final SettingGroup sg = settings.getDefaultGroup();
	private final Setting<Boolean> darkness = sg.add(new BoolSetting.Builder()
			.name("darkness").description("Also remove the Darkness effect.").defaultValue(true).build());
	private final Setting<Boolean> nausea = sg.add(new BoolSetting.Builder()
			.name("nausea").description("Also remove the Nausea wobble.").defaultValue(false).build());

	public CustomAntiBlind() {
		super(Addon.CATEGORY, "custom-anti-blind", "Blindness no longer affects your vision.");
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		if (mc.player == null)
			return;
		// Client-side only: the fog/vision effect is driven by the effect being present on the client.
		if (mc.player.hasEffect(MobEffects.BLINDNESS))
			mc.player.removeEffectNoUpdate(MobEffects.BLINDNESS);
		if (darkness.get() && mc.player.hasEffect(MobEffects.DARKNESS))
			mc.player.removeEffectNoUpdate(MobEffects.DARKNESS);
		if (nausea.get() && mc.player.hasEffect(MobEffects.NAUSEA))
			mc.player.removeEffectNoUpdate(MobEffects.NAUSEA);
	}
}
