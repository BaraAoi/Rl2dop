package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.net.WalletActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Кошелёк: три номинала монет в одном предмете. Все суммы считает сервер — экран только
 * показывает состояние и отправляет «положить» / «достать» / «разменять».
 *
 * <p>Фон рисуем сами, ванильный блюр отключён, чтобы панель была чёткой, а не размытой.
 */
public class WalletScreen extends Screen {
	private static final int WIDTH = 250;
	private static final int HEIGHT = 158;

	private int panelX;
	private int panelY;

	public WalletScreen() {
		super(Component.literal("Кошелёк"));
	}

	public static void handleOpen() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.screen instanceof WalletScreen)) {
			minecraft.setScreen(new WalletScreen());
		}
	}

	@Override
	protected void init() {
		this.panelX = (this.width - WIDTH) / 2;
		this.panelY = (this.height - HEIGHT) / 2;
		int x = this.panelX + 14;
		int y = this.panelY + HEIGHT - 28;
		this.addRenderableWidget(button("Положить всё", 0, x, y, 70));
		this.addRenderableWidget(button("Достать всё", 1, x + 74, y, 70));
		this.addRenderableWidget(button("Разменять", 2, x + 148, y, 52));
	}

	private Button button(String label, int action, int x, int y, int width) {
		return Button.builder(Component.literal(label),
						pressed -> ClientPlayNetworking.send(new WalletActionPayload(action)))
				.bounds(x, y, width, 20)
				.build();
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int x = this.panelX;
		int y = this.panelY;
		graphics.fill(x - 3, y + 4, x + WIDTH + 3, y + HEIGHT + 5, 0x60000000);
		graphics.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, 0xFF1B1208);
		graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF8A6B3F);
		graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF2181410);
		graphics.fill(x, y, x + WIDTH, y + 22, 0xFF241B12);
		graphics.drawCenteredString(this.font, this.title, x + WIDTH / 2, y + 8, 0xFFFFF2CC);

		int rowY = y + 32;
		graphics.drawString(this.font, "§6Золотые", x + 14, rowY, 0xFFFFFF, false);
		graphics.drawString(this.font, "§fx " + ClientEconomy.walletGold, x + WIDTH - 44, rowY, 0xFFFFFF, false);
		graphics.drawString(this.font, "§fЖелезные", x + 14, rowY + 14, 0xFFFFFF, false);
		graphics.drawString(this.font, "§fx " + ClientEconomy.walletIron, x + WIDTH - 44, rowY + 14, 0xFFFFFF, false);
		graphics.drawString(this.font, "§fБронзовые", x + 14, rowY + 28, 0xFFFFFF, false);
		graphics.drawString(this.font, "§fx " + ClientEconomy.walletBronze, x + WIDTH - 44, rowY + 28, 0xFFFFFF, false);
		graphics.fill(x + 12, rowY + 44, x + WIDTH - 12, rowY + 45, 0xFF6E5630);
		graphics.drawString(this.font, "§7" + ClientEconomy.walletSummary, x + 14, rowY + 50, 0xFFFFFF, false);
		graphics.drawString(this.font, "§7Всего со мной: §f" + ClientEconomy.coins + "м",
				x + 14, rowY + 62, 0xFFFFFF, false);
		graphics.drawString(this.font, "§7Монеты в карманах — предметы; «Положить всё» соберёт их в кошелёк",
				x + 6, y + HEIGHT - 42, 0xFFE8E0C8, true);
		super.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
