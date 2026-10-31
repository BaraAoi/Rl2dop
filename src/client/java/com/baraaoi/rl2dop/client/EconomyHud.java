package com.baraaoi.rl2dop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Простой HUD-оверлей: монеты, репутация, поселение, состояние эмиссии. */
public final class EconomyHud {
	private EconomyHud() {
	}

	public static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.options.hideGui) {
			return;
		}
		Font font = minecraft.font;
		int x = 6;
		int y = 6;
		graphics.drawString(font, "§6Казна: §f" + ClientEconomy.coins + "м"
				+ (ClientEconomy.wallet ? "" : " §8(нет кошелька)"), x, y, 0xFFFFFF, true);
		y += 10;
		if (!ClientEconomy.settlement.isEmpty()) {
			String repColor = ClientEconomy.reputation >= 0 ? "§a" : "§c";
			graphics.drawString(font, "§7" + ClientEconomy.settlement + " §8· " + repColor
					+ "реп " + ClientEconomy.reputation, x, y, 0xFFFFFF, true);
			y += 10;
		}
		if (!ClientEconomy.ticker.isEmpty()) {
			graphics.drawString(font, "§8" + ClientEconomy.ticker, x, y, 0xFFFFFF, true);
		}
	}
}
