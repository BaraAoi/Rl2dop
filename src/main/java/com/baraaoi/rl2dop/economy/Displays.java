package com.baraaoi.rl2dop.economy;

import com.baraaoi.rl2dop.Rl2dopConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * "Полки" и табло: невидимые armor stand'ы с текстом, который обновляется из серверных данных.
 * Сами предметы на полке — только витрина, торговля идёт через {@link TradeService}.
 */
public final class Displays {
	private Displays() {
	}

	private static final String SHELF_TAG = "rl2dop_shelf";
	private static final String BOARD_TAG = "rl2dop_board";

	public static void refresh(MinecraftServer server, EconomyState state) {
		for (Settlement settlement : state.all()) {
			ServerLevel level = levelOf(server, settlement.dimension);
			if (level == null) {
				continue;
			}
			if (settlement.boardPos != Long.MIN_VALUE) {
				BlockPos pos = BlockPos.of(settlement.boardPos);
				if (level.hasChunkAt(pos)) {
					ArmorStand stand = findOrCreate(level, pos, BOARD_TAG);
					if (stand != null) {
						stand.setCustomName(Component.literal(ShopService.boardText(settlement)));
					}
				}
			}
			for (String entry : settlement.shelves) {
				updateShelf(level, settlement, entry);
			}
		}
	}

	public static void ensureBoard(ServerLevel level, Settlement settlement, BlockPos pos) {
		settlement.boardPos = pos.asLong();
		ArmorStand stand = findOrCreate(level, pos, BOARD_TAG);
		if (stand != null) {
			stand.setCustomName(Component.literal(ShopService.boardText(settlement)));
		}
		EconomyState.get(level.getServer()).setDirty();
	}

	public static boolean addShelf(ServerLevel level, Settlement settlement, BlockPos pos, String itemId) {
		if (Rl2dopConfig.good(itemId) == null) {
			return false;
		}
		String entry = pos.getX() + ";" + pos.getY() + ";" + pos.getZ() + ";" + itemId;
		if (!settlement.shelves.contains(entry)) {
			settlement.shelves.add(entry);
		}
		updateShelf(level, settlement, entry);
		EconomyState.get(level.getServer()).setDirty();
		return true;
	}

	private static void updateShelf(ServerLevel level, Settlement settlement, String entry) {
		String[] parts = entry.split(";");
		if (parts.length != 4) {
			return;
		}
		BlockPos pos;
		try {
			pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return;
		}
		if (!level.hasChunkAt(pos)) {
			return;
		}
		String itemId = parts[3];
		Item item = resolve(itemId);
		int stock = settlement.stock(itemId);
		double price = settlement.price(itemId);
		String text = "§e" + new ItemStack(item).getHoverName().getString()
				+ " §7· §a" + String.format(Locale.ROOT, "%.1f", price) + "м"
				+ " §7· сток §f" + stock;
		ArmorStand stand = findOrCreate(level, pos, SHELF_TAG);
		if (stand != null) {
			stand.setCustomName(Component.literal(text));
		}
	}

	private static ArmorStand findOrCreate(ServerLevel level, BlockPos pos, String tag) {
		AABB area = new AABB(pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D).inflate(1.5D);
		List<ArmorStand> found = level.getEntitiesOfClass(ArmorStand.class, area,
				stand -> stand.getTags().contains(tag));
		if (!found.isEmpty()) {
			return found.get(0);
		}
		ArmorStand stand = EntityType.ARMOR_STAND.create(level);
		if (stand == null) {
			return null;
		}
		stand.moveTo(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D, 0.0F, 0.0F);
		stand.setInvisible(true);
		stand.setNoGravity(true);
		stand.setInvulnerable(true);
		stand.setSilent(true);
		stand.setMarker(true);
		stand.setCustomNameVisible(true);
		stand.addTag(tag);
		level.addFreshEntity(stand);
		return stand;
	}

	public static Item resolve(String itemId) {
		ResourceLocation id = ResourceLocation.tryParse(itemId);
		if (id == null) {
			return Items.AIR;
		}
		Item item = BuiltInRegistries.ITEM.get(id);
		return item == null ? Items.AIR : item;
	}

	public static ServerLevel levelOf(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}
}
