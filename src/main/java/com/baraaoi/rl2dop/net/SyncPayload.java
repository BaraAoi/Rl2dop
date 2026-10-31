package com.baraaoi.rl2dop.net;

import com.baraaoi.rl2dop.Rl2dop;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C: монеты в кармане, репутация, поселение и строка экономики для HUD. */
public record SyncPayload(int coins, int reputation, String settlement, String ticker) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Rl2dop.id("sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SyncPayload::coins,
			ByteBufCodecs.VAR_INT, SyncPayload::reputation,
			ByteBufCodecs.STRING_UTF8, SyncPayload::settlement,
			ByteBufCodecs.STRING_UTF8, SyncPayload::ticker,
			SyncPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
