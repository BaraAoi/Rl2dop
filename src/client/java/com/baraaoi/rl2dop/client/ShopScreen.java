package com.baraaoi.rl2dop.client;

import com.baraaoi.rl2dop.economy.TradeService;
import com.baraaoi.rl2dop.net.ShopPayload;
import com.baraaoi.rl2dop.net.TradeActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран покупки/продажи. Никаких слотов и предметов — только кнопки, все суммы пересчитывает
 * сервер, поэтому дюпнуть через экран нельзя.
 *
 * <p>Фон окна рисуем сами и отключаем ванильный блюр: иначе Minecraft размывает всё под экраном
 * и панель выглядит частью фона, а не окном.
 */
public class ShopScreen extends Screen {
	private static final int ROW_HEIGHT = 16;
	private static final int LIST_TOP = 30;
	private static final int[] AMOUNTS = {1, 8, 16, 32, 64};

	private int citizenId;
	private String purse = "";
	private final List<Row> rows = new ArrayList<>();
	private int selected;
	private int amountIndex = 0;

	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int listTop;

	private record Row(String itemId, String name, int buy, int sell, int stock, int demand, int held) {
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
		purse = payload.purse() == null ? "" : payload.purse();
		rows.clear();
		for (String line : payload.goods()) {
			String[] parts = line.split("\\|");
			if (parts.length < 5) {
				continue;
			}
			try {
				int held = parts.length >= 6 ? Integer.parseInt(parts[5]) : 0;
				rows.add(new Row(parts[0], itemName(parts[0]), Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), held));
			} catch (NumberFormatException ignored) {
				// битая строка — пропускаем
			}
		}
		if (selected >= rows.size()) {
			selected = Math.max(0, rows.size() - 1);
		}
	}

	private int layoutHeight() {
		return Math.min(this.height - 20, LIST_TOP + rows.size() * ROW_HEIGHT + 76);
	}

	@Override
	protected void init() {
		this.panelWidth = Math.min(this.width - 20, 340);
		this.panelHeight = layoutHeight();
		this.panelX = (this.width - this.panelWidth) / 2;
		this.panelY = (this.height - this.panelHeight) / 2;
		this.listTop = this.panelY + LIST_TOP;

		int y = this.panelY + this.panelHeight - 44;
		int x = this.panelX + 10;
		for (int value : AMOUNTS) {
			boolean active = amount() == value;
			this.addRenderableWidget(Button.builder(
							Component.literal(active ? "§e" + value : "§8" + value),
							pressed -> {
								pickAmount(value);
								rebuildWidgets();
							})
					.bounds(x, y, 22, 20)
					.build());
			x += 25;
		}
		this.addRenderableWidget(action("Купить", TradeService.ACTION_BUY, panelX + 145, y, 62));
		this.addRenderableWidget(action("Продать", TradeService.ACTION_SELL, panelX + 211, y, 62));
		this.addRenderableWidget(action("Торг", TradeService.ACTION_HAGGLE, panelX + 277, y, 46));
	}

	private Button action(String label, int action, int x, int y, int width) {
		return Button.builder(Component.literal(label), pressed -> perform(action, amount()))
				.bounds(x, y, width, 20)
				.build();
	}

	private void pickAmount(int value) {
		for (int i = 0; i < AMOUNTS.length; i++) {
			if (AMOUNTS[i] == value) {
				amountIndex = i;
			}
		}
	}

	private int amount() {
		return AMOUNTS[Math.max(0, Math.min(AMOUNTS.length - 1, amountIndex))];
	}

	private void perform(int action, int amount) {
		if (rows.isEmpty()) {
			return;
		}
		Row row = rows.get(Math.max(0, Math.min(rows.size() - 1, selected)));
		ClientPlayNetworking.send(new TradeActionPayload(citizenId, row.itemId(), amount, action));
	}

	/** Без ванильного блюра и тайловой текстуры меню: окно должно быть чётким. */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		if (this.panelHeight != layoutHeight() || this.panelWidth != Math.min(this.width - 20, 340)) {
			rebuildWidgets();
		}
		drawPanel(graphics);

		var font = this.font;
		graphics.drawString(font, "§7Товар", panelX + 34, listTop - 13, 0xFFFFFF, false);
		graphics.drawString(font, "§7покупка / продажа", panelX + panelWidth - 122, listTop - 13, 0xFFFFFF, false);
		graphics.drawString(font, "§7сток / у тебя", panelX + panelWidth - 232, listTop - 13, 0xFFFFFF, false);

		int y = listTop;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			boolean hovered = mouseX >= panelX + 8 && mouseX <= panelX + panelWidth - 8
					&& mouseY >= y - 2 && mouseY < y + ROW_HEIGHT - 2;
			if (i == selected) {
				graphics.fill(panelX + 8, y - 3, panelX + panelWidth - 8, y + ROW_HEIGHT - 3, 0x50FFD966);
				graphics.fill(panelX + 8, y + ROW_HEIGHT - 4, panelX + panelWidth - 8, y + ROW_HEIGHT - 3, 0x60FFD966);
			} else if (hovered) {
				graphics.fill(panelX + 8, y - 3, panelX + panelWidth - 8, y + ROW_HEIGHT - 3, 0x28FFFFFF);
			}
			ItemStack stack = icon(row.itemId());
			graphics.renderItem(stack, panelX + 12, y - 3);
			int nameColor = i == selected ? 0xFFFFE066 : (hovered ? 0xFFFFFFFF : 0xFFB8B8B8);
			graphics.drawString(font, row.name(), panelX + 34, y + 1, nameColor, false);
			String prices = (i == selected ? "§e" : "§a") + row.buy() + "м §8/ §6" + row.sell() + "м";
			graphics.drawString(font, prices, panelX + panelWidth - 122, y + 1, 0xFFFFFF, false);
			String stocks = "§f" + row.stock() + (row.held() > 0 ? " §8/ §b" + row.held() : " §8/ ·");
			graphics.drawString(font, stocks, panelX + panelWidth - 232, y + 1, 0xFFFFFF, false);
			if (row.demand() > 0) {
				graphics.drawString(font, "§3▲", panelX + panelWidth - 138, y + 1, 0xFFFFFF, false);
			}
			y += ROW_HEIGHT;
		}
		if (rows.isEmpty()) {
			graphics.drawString(font, "§7Нет товаров в продаже.", panelX + 34, listTop, 0xFFFFFF, false);
		}

		super.render(graphics, mouseX, mouseY, delta);

		graphics.drawString(font, "§7Кошелёк: §f" + purse, panelX + 10, panelY + panelHeight - 60, 0xFFFFFF, false);
		graphics.drawString(font, "§8ЛКМ — выбрать, ПКМ — продать, колесо/стрелки — список",
				panelX + panelWidth - 232, panelY + panelHeight - 18, 0xFFFFFF, false);
		graphics.drawString(font, "§8" + (rows.isEmpty() ? "—" : rows.get(selected).name())
						+ " §8· §7" + amount() + " шт §8| §7Esc — закрыть",
				panelX + 10, panelY + panelHeight - 18, 0xFFFFFF, false);
		if (hoveredRow(mouseX, mouseY) >= 0) {
			graphics.renderComponentTooltip(font, List.of(
					Component.literal("§aКупить " + amount() + " шт §7за "
							+ rows.get(hoveredRow(mouseX, mouseY)).buy() * amount() + "м"),
					Component.literal("§6Продать " + amount() + " шт §7за "
							+ rows.get(hoveredRow(mouseX, mouseY)).sell() * amount() + "м"),
					Component.literal("§7спрос поселения: §f" + rows.get(hoveredRow(mouseX, mouseY)).demand() + "%")
			), mouseX, mouseY);
		}
	}

	private void drawPanel(GuiGraphics graphics) {
		int x = this.panelX;
		int y = this.panelY;
		int w = this.panelWidth;
		int h = this.panelHeight;
		// тень, рамка, «дерево» окна
		graphics.fill(x - 3, y + 4, x + w + 3, y + h + 5, 0x60000000);
		graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF2A1C10);
		graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF6B4A2A);
		graphics.fill(x, y, x + w, y + h, 0xFFE7D6AE);
		graphics.fill(x, y, x + w, y + 22, 0xFF3A2716);
		graphics.fill(x, y + h - 26, x + w, y + h, 0xFFDCC496);
		for (int i = 0; i < h - 48; i += 4) {
			graphics.fill(x, y + 22 + i, x + 1, y + 24 + i, 0xC0000000);
			graphics.fill(x + w - 1, y + 22 + i, x + w, y + 24 + i, 0xC0000000);
		}
		graphics.drawCenteredString(this.font, this.title, x + w / 2, y + 8, 0xFFFFD966);
	}

	private int hoveredRow(int mouseX, int mouseY) {
		if (mouseX < panelX + 8 || mouseX > panelX + panelWidth - 8) {
			return -1;
		}
		if (mouseY < listTop - 3 || mouseY >= listTop + rows.size() * ROW_HEIGHT) {
			return -1;
		}
		int index = (mouseY - listTop + 3) / ROW_HEIGHT;
		return index >= 0 && index < rows.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int index = hoveredRow((int) mouseX, (int) mouseY);
		if (index >= 0 && button == 0) {
			selected = index;
			return true;
		}
		if (index >= 0 && button == 1) {
			perform(TradeService.ACTION_SELL, amount());
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 258) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (!rows.isEmpty()) {
			if (keyCode == 265) {
				selected = (selected + rows.size() - 1) % rows.size();
				return true;
			}
			if (keyCode == 264) {
				selected = (selected + 1) % rows.size();
				return true;
			}
			if (keyCode == 257 || keyCode == 32) {
				perform(TradeService.ACTION_BUY, amount());
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private ItemStack icon(String itemId) {
		try {
			return new ItemStack(TradeService.resolveItem(itemId));
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	private static String itemName(String itemId) {
		try {
			ItemStack stack = new ItemStack(TradeService.resolveItem(itemId));
			String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
			return name == null || name.isBlank() ? itemId : name;
		} catch (Exception e) {
			return itemId;
		}
	}
}
