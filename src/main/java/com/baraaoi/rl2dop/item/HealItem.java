package com.baraaoi.rl2dop.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Бинт / аптечка: лечит игрока (и через ИИ лекаря — NPC). */
public class HealItem extends Item {
	private final float healAmount;
	private final int cooldownTicks;

	public HealItem(float healAmount, int cooldownTicks, Item.Properties properties) {
		super(properties);
		this.healAmount = healAmount;
		this.cooldownTicks = cooldownTicks;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.getHealth() >= player.getMaxHealth()) {
			return InteractionResultHolder.fail(stack);
		}
		if (!level.isClientSide) {
			player.heal(healAmount);
			stack.shrink(1);
			if (cooldownTicks > 0) {
				player.getCooldowns().addCooldown(this, cooldownTicks);
			}
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
	}
}
