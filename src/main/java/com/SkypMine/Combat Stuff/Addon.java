package com.kkllffaa.meteor_litematica_printer;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

/** Made by skypmine. */
public class Addon extends MeteorAddon {

	public static final Logger LOG = LogUtils.getLogger();
	public static final Category CATEGORY = new Category("Skypmine", () -> new ItemStack(Items.END_CRYSTAL));

	@Override
	public void onInitialize() {
		LOG.info("Initializing Skypmine Combat Addon (made by skypmine)");
		Modules.get().add(new CustomTriggerBot());
		Modules.get().add(new FastEat());
		Modules.get().add(new CustomKillAura());
		Modules.get().add(new CustomAntiBlind());
		Modules.get().add(new CustomCrystalAura());
		Modules.get().add(new CustomEChestFarmer());
		Modules.get().add(new TntManiac());
	}

	@Override
	public void onRegisterCategories() {
		Modules.registerCategory(CATEGORY);
	}

	@Override
	public String getPackage() {
		return "com.kkllffaa.meteor_litematica_printer";
	}
}
