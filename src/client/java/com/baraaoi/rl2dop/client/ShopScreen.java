package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.economy.TradeService;
import com.baraaoi.rl2dop.net.ShopPayload;
import com.baraaoi.rl2dop.net.TradeActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран покупки/продажи. Никаких слотов и предметов — только кнопки.
 * Все суммы пересчитывает сервер, поэтому дюпнуть через экран нельзя.
 */
public class ShopScreen extends Screen {
	private static final int ROW_HEIGHT = 12;

	private int citizenId;
	private final List<Row> rows = new ArrayList<>();
	private int selected;
	private int listTop;

	private record Row(String itemId, String name, int buy, int sell, int stock, int demand) {
	}

	public ShopScreen(ShopPayload payload) {
		super(Component.literal(payload.title()));
		load(payload);
	}

	public static void handle(ShopPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof ShopScreen screen) {
			screen.load(payload);
			screen.rebuildWidgets();
		} else {
			minecraft.setScreen(new ShopScreen(payload));
		}
	}

	private void load(ShopPayload payload) {
		citizenId = payload.citizenId();
		rows.clear();
		for (String line : payload.goods()) {
			String[] parts = line.split("\\|");
			if (parts.length != 5) {
				continue;
			}
			try {
				rows.add(new Row(parts[0], itemName(parts[0]), Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
			} catch (NumberFormatException ignored) {
				// битая строка — пропускаем
			}
		}
		if (selected >= rows.size()) {
			selected = Math.max(0, rows.size() - 1);
		}
	}

	@Override
	protected void init() {
		listTop = 34;
		int y = this.height - 46;
		int buttonWidth = 84;
		int gap = 6;
		int total = buttonWidth * 5 + gap * 4;
		int startX = (this.width - total) / 2;
		addRenderableWidget(button("Купить 1", startX, y, buttonWidth, 0, 1));
		addRenderableWidget(button("Купить 8", startX + buttonWidth + gap, y, buttonWidth, 0, 8));
		addRenderableWidget(button("Продать 1", startX + (buttonWidth + gap) * 2, y, buttonWidth, 1, 1));
		addRenderableWidget(button("Продать 8", startX + (buttonWidth + gap) * 3, y, buttonWidth, 1, 8));
		addRenderableWidget(button("Торг", startX + (buttonWidth + gap) * 4, y, buttonWidth, 2, 0));
	}

	private Button button(String label, int x, int y, int width, int action, int amount) {
		return Button.builder(Component.literal(label), pressed -> perform(action, amount))
				.bounds(x, y, width, 20)
				.build();
	}

	private void perform(int action, int amount) {
		if (rows.isEmpty()) {
			return;
		}
		Row row = rows.get(Math.max(0, Math.min(rows.size() - 1, selected)));
		ClientPlayNetworking.send(new TradeActionPayload(citizenId, row.itemId(), amount, action));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFD966);
		graphics.drawString(this.font, "§7Товар", 20, listTop - 12, 0xFFFFFF, true);
		graphics.drawString(this.font, "§7Цена / скупка / сток / спрос", this.width - 230, listTop - 12, 0xFFFFFF, true);

		int y = listTop;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			boolean hovered = mouseX >= 16 && mouseX <= this.width - 16 && mouseY >= y - 2 && mouseY < y + ROW_HEIGHT - 2;
			int color = i == selected ? 0xFFFFE066 : (hovered ? 0xFFFFFFFF : 0xFFB0B0B0);
			if (i == selected) {
				graphics.fill(14, y - 2, this.width - 14, y + ROW_HEIGHT - 2, 0x60FFD966);
			}
			graphics.drawString(this.font, row.name(), 20, y, color, true);
			String prices = "§a" + row.buy() + "м §8/ §e" + row.sell() + "м §8/ §f" + row.stock()
					+ " §8/ §b" + row.demand() + "%";
			graphics.drawString(this.font, prices, this.width - 230, y, 0xFFFFFF, true);
			y += ROW_HEIGHT;
		}
		if (rows.isEmpty()) {
			graphics.drawString(this.font, "§7Нет товаров в продаже.", 20, listTop, 0xFFFFFF, true);
		}
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawString(this.font, "§8Выбрано: " + (rows.isEmpty() ? "—" : rows.get(selected).name())
				+ "  §8| §7Esc — закрыть", 20, this.height - 22, 0xFFFFFF, true);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (mouseY >= listTop - 2 && mouseY < listTop + rows.size() * ROW_HEIGHT) {
			int index = (int) ((mouseY - listTop + 2) / ROW_HEIGHT);
			if (index >= 0 && index < rows.size()) {
				selected = index;
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static String itemName(String itemId) {
		try {
			ItemStack stack = new ItemStack(TradeService.resolveItem(itemId));
			return stack.getHoverName().getString();
		} catch (Exception e) {
			return itemId;
		}
	}
}
