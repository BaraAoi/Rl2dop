package com.baraaoi.rl2dop.item;

import com.baraaoi.rl2dop.economy.CoinType;
import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Старая монета {@code rl2dop:coin} из первых версий. Нужна только для того, чтобы предметы в
 * сундуках и чанках не превратились в воздух: меняются на бронзу 1:1 при входе игрока
 * ({@code LegacyCoins}) или по правой кнопке мыши.
 */
public class LegacyCoinItem extends Item {
	public LegacyCoinItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!level.isClientSide && !stack.isEmpty()) {
			int count = stack.getCount();
			ItemStack bronze = new ItemStack(CoinType.BRONZE.item(), count);
			player.setItemInHand(hand, player.getInventory().add(bronze) ? ItemStack.EMPTY : bronze);
			if (player instanceof ServerPlayer serverPlayer) {
				serverPlayer.displayClientMessage(Component.literal("§aОбменяно §f" + count
						+ " §aстарых монет на бронзу (§f" + count + "м§a)"), false);
			}
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.literal("Устарела: ПКМ — обменять на бронзовую монету 1:1")
				.withStyle(ChatFormatting.GRAY));
	}
}
