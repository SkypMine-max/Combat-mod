package com.kkllffaa.meteor_litematica_printer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Shared critical-hit helper used by TriggerBot and KillAura. */
public final class CritUtil {

	private CritUtil() {
	}

	public enum CritMode {
		Off, // normal hits
		Packet, // spoof a tiny jump with packets -> crit every hit, even standing still
		WaitForFall // legit: only hit while actually falling
	}

	/** For WaitForFall: is a hit allowed right now? Other modes always true. */
	public static boolean canHit(CritMode mode) {
		if (mode != CritMode.WaitForFall)
			return true;
		LocalPlayer p = mc.player;
		return !p.onGround() && p.fallDistance > 0 && !p.isInWater() && !p.onClimbable();
	}

	/** Call right BEFORE the attack. */
	public static void apply(CritMode mode) {
		if (mode != CritMode.Packet)
			return;
		LocalPlayer p = mc.player;
		if (!p.onGround() || p.isInWater() || p.isInLava() || p.onClimbable() || p.isPassenger())
			return;
		if (mc.getConnection() == null)
			return;

		double x = p.getX(), y = p.getY(), z = p.getZ();
		// Server sees a small hop up then a not-on-ground position at original height,
		// so fallDistance > 0 and the following hit counts as a critical.
		mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, p.horizontalCollision));
		mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, p.horizontalCollision));
	}
}
