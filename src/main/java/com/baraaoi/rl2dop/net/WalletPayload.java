package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dop;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: кошелёк. Три счёта монет внутри кошелька плюс готовая строка «что в карманах и сколько
 * всего» — цифры считает сервер, клиент только рисует.
 */
public record WalletPayload(int bronze, int iron, int gold, String summary) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<WalletPayload> TYPE =
			new CustomPacketPayload.Type<>(Rl2dop.id("wallet"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WalletPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, WalletPayload::bronze,
			ByteBufCodecs.VAR_INT, WalletPayload::iron,
			ByteBufCodecs.VAR_INT, WalletPayload::gold,
			ByteBufCodecs.STRING_UTF8, WalletPayload::summary,
			WalletPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
