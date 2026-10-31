package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dop;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: содержимое торгового экрана.
 * Строки ленты: {@code itemId|buyPrice|sellPrice|stock|demand}.
 */
public record ShopPayload(int citizenId, String title, List<String> goods) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShopPayload> TYPE =
			new CustomPacketPayload.Type<>(Rl2dop.id("shop"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShopPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShopPayload::citizenId,
			ByteBufCodecs.STRING_UTF8, ShopPayload::title,
			ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), ShopPayload::goods,
			ShopPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
