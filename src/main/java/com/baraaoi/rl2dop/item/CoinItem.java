package com.baraaoi.rl2dop.item;

import net.minecraft.world.item.Item;

/** Физическая монета. Весь учёт эмиссии живёт на сервере в {@code EconomyState}. */
public class CoinItem extends Item {
	public CoinItem(Item.Properties properties) {
		super(properties);
	}
}
