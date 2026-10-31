package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dop;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: "хочу купить/продать/поторговаться". Сервер всё пересчитывает сам. */
public record TradeActionPayload(int citizenId, String itemId, int amount, int action) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TradeActionPayload> TYPE =
			new CustomPacketPayload.Type<>(Rl2dop.id("trade_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TradeActionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, TradeActionPayload::citizenId,
			ByteBufCodecs.STRING_UTF8, TradeActionPayload::itemId,
			ByteBufCodecs.VAR_INT, TradeActionPayload::amount,
			ByteBufCodecs.VAR_INT, TradeActionPayload::action,
			TradeActionPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
