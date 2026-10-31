package com.baraaoi.rl2dop.item;

import com.baraaoi.rl2dop.economy.CoinType;
import com.baraaoi.rl2dop.economy.Money;
import com.baraaoi.rl2dop.economy.WalletService;
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
 * Кошелёк: носит все три номинала внутри одного предмета (в компоненте custom_data).
 * ПКМ открывает экран кошелька, там же «положить всё» и «достать всё».
 */
public class WalletItem extends Item {
	public WalletItem(Item.Properties properties) {
		super(properties.stacksTo(1));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			WalletService.open(serverPlayer);
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		int[] counts = Money.walletCounts(stack);
		tooltip.add(Component.literal("Золото: " + counts[CoinType.GOLD.ordinal()]).withStyle(ChatFormatting.GOLD));
		tooltip.add(Component.literal("Железо: " + counts[CoinType.IRON.ordinal()]).withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Бронза: " + counts[CoinType.BRONZE.ordinal()]).withStyle(ChatFormatting.GOLD));
		tooltip.add(Component.literal("Всего: " + Money.value(counts) + "м").withStyle(ChatFormatting.YELLOW));
		tooltip.add(Component.literal("ПКМ — открыть кошелёк").withStyle(ChatFormatting.DARK_GRAY));
	}
}
