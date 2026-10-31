package com.baraaoi.rl2dop.entity;

import com.baraaoi.rl2dop.Rl2dopConfig;
import com.baraaoi.rl2dop.economy.EconomyState;
import com.baraaoi.rl2dop.economy.Settlement;
import com.baraaoi.rl2dop.economy.SettlementService;
import com.baraaoi.rl2dop.economy.ShopService;
import com.baraaoi.rl2dop.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Житель поселения: имя, профессия, кошелёк, переносимый инвентарь, голод,
 * дом/работа/магазин и простое состояние. Всё экономическое — на сервере.
 */
public class CitizenEntity extends PathfinderMob {
	public static final int INVENTORY_SLOTS = 9;

	private Profession profession = Profession.FARMER;
	private CitizenState citizenState = CitizenState.IDLE;
	private String citizenName = "";
	private String settlementId = "";
	private int wallet = 20;
	private int hunger = 100;
	private int mood = 60;
	private int workTicks;
	private int salaryTimer;
	private int professionCooldown;
	private int guardVariant;

	private BlockPos homePos;
	private BlockPos workPos;
	private BlockPos shopPos;

	private final Map<String, Integer> carried = new LinkedHashMap<>();

	public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false);
		this.xpReward = 0;
	}

	public static AttributeSupplier.Builder createCitizenAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0D)
				.add(Attributes.MOVEMENT_SPEED, 0.3D)
				.add(Attributes.ATTACK_DAMAGE, 2.0D)
				.add(Attributes.FOLLOW_RANGE, 24.0D)
				.add(Attributes.ARMOR, 2.0D);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15D, false));
		this.goalSelector.addGoal(7, new RandomStrollGoal(this, 0.5D));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide) {
			return;
		}
		if (this.tickCount % 400 == 0 && this.hunger > 0) {
			this.hunger = Math.max(0, this.hunger - 1);
		}
		int interval = Math.max(1, Rl2dopConfig.brainIntervalTicks);
		if (((int) (this.level().getGameTime() + this.getId())) % interval == 0) {
			CitizenBrain.tick(this);
		}
	}

	// ---------- данные ----------

	public Profession getProfession() {
		return profession;
	}

	public void setProfession(Profession profession) {
		Profession previous = this.profession;
		this.profession = profession;
		if (!this.level().isClientSide) {
			Settlement settlement = SettlementService.byId((ServerLevel) this.level(), settlementId);
			if (settlement != null && previous != profession) {
				settlement.adjustJob(previous, -1);
				settlement.adjustJob(profession, 1);
				EconomyState.get(this.level().getServer()).setDirty();
			}
		}
		applyEquipment();
		refreshNameTag();
	}

	public CitizenState getCitizenState() {
		return citizenState;
	}

	public void setCitizenState(CitizenState citizenState) {
		this.citizenState = citizenState;
	}

	public String getCitizenName() {
		return citizenName;
	}

	public void setCitizenName(String citizenName) {
		this.citizenName = citizenName == null ? "" : citizenName;
		refreshNameTag();
	}

	public String getSettlementId() {
		return settlementId;
	}

	public void setSettlementId(String settlementId) {
		this.settlementId = settlementId == null ? "" : settlementId;
		if (!this.level().isClientSide) {
			Settlement settlement = SettlementService.byId((ServerLevel) this.level(), this.settlementId);
			if (settlement != null) {
				settlement.registerNpc(this.getUUID());
				settlement.adjustJob(profession, 1);
			}
		}
	}

	public int getWallet() {
		return wallet;
	}

	public void setWallet(int wallet) {
		this.wallet = Math.max(0, wallet);
	}

	public void addWallet(int delta) {
		setWallet(this.wallet + delta);
	}

	public int getHunger() {
		return hunger;
	}

	public void setHunger(int hunger) {
		this.hunger = Math.max(0, Math.min(100, hunger));
	}

	public int getMood() {
		return mood;
	}

	public void setMood(int mood) {
		this.mood = Math.max(0, Math.min(100, mood));
	}

	public int getWorkTicks() {
		return workTicks;
	}

	public void setWorkTicks(int workTicks) {
		this.workTicks = Math.max(0, workTicks);
	}

	public int getSalaryTimer() {
		return salaryTimer;
	}

	public void setSalaryTimer(int salaryTimer) {
		this.salaryTimer = Math.max(0, salaryTimer);
	}

	public int getProfessionCooldown() {
		return professionCooldown;
	}

	public void setProfessionCooldown(int professionCooldown) {
		this.professionCooldown = Math.max(0, professionCooldown);
	}

	public boolean isArcherGuard() {
		return profession == Profession.GUARD && guardVariant == 1;
	}

	public void setGuardVariant(int guardVariant) {
		this.guardVariant = guardVariant;
		applyEquipment();
	}

	public BlockPos getHomePos() {
		return homePos;
	}

	public void setHomePos(BlockPos homePos) {
		this.homePos = homePos;
	}

	public BlockPos getWorkPos() {
		return workPos;
	}

	public void setWorkPos(BlockPos workPos) {
		this.workPos = workPos;
	}

	public BlockPos getShopPos() {
		return shopPos;
	}

	public void setShopPos(BlockPos shopPos) {
		this.shopPos = shopPos;
	}

	// ---------- переносимый инвентарь (учёт в NBT, без ItemStack) ----------

	public int carried(String itemId) {
		return carried.getOrDefault(itemId, 0);
	}

	public void addCarried(String itemId, int amount) {
		int next = carried(itemId) + amount;
		if (next <= 0) {
			carried.remove(itemId);
		} else {
			carried.put(itemId, Math.min(Settlement.MAX_STOCK, next));
		}
	}

	public Map<String, Integer> carriedView() {
		return carried;
	}

	// ---------- экипировка / имя ----------

	private void applyEquipment() {
		ItemStack hand = ItemStack.EMPTY;
		if (profession == Profession.GUARD) {
			hand = new ItemStack(guardVariant == 1 ? Items.BOW : Items.IRON_SWORD);
		} else if (profession == Profession.FARMER) {
			hand = new ItemStack(Items.IRON_HOE);
		} else if (profession == Profession.LUMBERJACK) {
			hand = new ItemStack(Items.IRON_AXE);
		} else if (profession == Profession.MINER) {
			hand = new ItemStack(Items.IRON_PICKAXE);
		} else if (profession == Profession.HEALER) {
			hand = new ItemStack(ModItems.BANDAGE);
		}
		this.setItemSlot(EquipmentSlot.MAINHAND, hand);
		this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	public void refreshNameTag() {
		String name = citizenName.isEmpty() ? "Житель" : citizenName;
		Component tag = Component.literal("§e" + name + " ")
				.append(Component.translatable(profession.translationKey()).withStyle(ChatFormatting.GRAY));
		this.setCustomName(tag);
		this.setCustomNameVisible(true);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	// ---------- взаимодействие ----------

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (this.level().isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			if (serverPlayer.isShiftKeyDown()) {
				serverPlayer.displayClientMessage(Component.literal("§e" + citizenName + "§7 · "
						+ Component.translatable(profession.translationKey()).getString()
						+ " · " + Component.translatable(citizenState.translationKey()).getString()
						+ " · кошелёк " + wallet + "м · голод " + hunger + "% · настрой " + mood + "%"), false);
				return InteractionResult.CONSUME;
			}
			if (profession == Profession.TRADER || profession == Profession.HEALER) {
				ShopService.open(serverPlayer, this);
				return InteractionResult.CONSUME;
			}
			serverPlayer.displayClientMessage(Component.literal("§7" + citizenName + " занят работой. Shift+ПКМ — сведения."), false);
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		boolean hurt = super.hurt(source, amount);
		if (hurt && !this.level().isClientSide && source.getEntity() instanceof ServerPlayer player) {
			Settlement settlement = SettlementService.byId((ServerLevel) this.level(), settlementId);
			if (settlement != null) {
				settlement.addReputation(player.getUUID(), -Rl2dopConfig.npcHitReputationPenalty);
				settlement.addDemand("rl2dop:bandage", 2);
				EconomyState.get(this.level().getServer()).setDirty();
			}
			this.setCitizenState(CitizenState.PANIC);
			this.playSound(SoundEvents.VILLAGER_HURT, 1.0F, 1.0F);
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!this.level().isClientSide) {
			Settlement settlement = SettlementService.byId((ServerLevel) this.level(), settlementId);
			if (settlement != null) {
				settlement.unregisterNpc(this.getUUID());
				settlement.adjustJob(profession, -1);
				EconomyState.get(this.level().getServer()).setDirty();
			}
		}
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		if (wallet > 0) {
			int drop = Math.min(wallet, 64);
			this.spawnAtLocation(new ItemStack(ModItems.COIN, drop));
			wallet -= drop;
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString("Profession", profession.id());
		tag.putString("CitizenState", citizenState.id());
		tag.putString("CitizenName", citizenName);
		tag.putString("Settlement", settlementId);
		tag.putInt("Wallet", wallet);
		tag.putInt("Hunger", hunger);
		tag.putInt("Mood", mood);
		tag.putInt("WorkTicks", workTicks);
		tag.putInt("SalaryTimer", salaryTimer);
		tag.putInt("ProfCooldown", professionCooldown);
		tag.putInt("GuardVariant", guardVariant);
		if (homePos != null) {
			tag.putLong("Home", homePos.asLong());
		}
		if (workPos != null) {
			tag.putLong("Work", workPos.asLong());
		}
		if (shopPos != null) {
			tag.putLong("Shop", shopPos.asLong());
		}
		ListTag carriedTag = new ListTag();
		for (Map.Entry<String, Integer> entry : carried.entrySet()) {
			CompoundTag item = new CompoundTag();
			item.putString("Id", entry.getKey());
			item.putInt("Count", entry.getValue());
			carriedTag.add(item);
		}
		tag.put("Carried", carriedTag);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		profession = Profession.byId(tag.getString("Profession"));
		citizenState = CitizenState.byId(tag.getString("CitizenState"));
		citizenName = tag.getString("CitizenName");
		settlementId = tag.getString("Settlement");
		wallet = tag.getInt("Wallet");
		hunger = tag.contains("Hunger") ? tag.getInt("Hunger") : 100;
		mood = tag.contains("Mood") ? tag.getInt("Mood") : 60;
		workTicks = tag.getInt("WorkTicks");
		salaryTimer = tag.getInt("SalaryTimer");
		professionCooldown = tag.getInt("ProfCooldown");
		guardVariant = tag.getInt("GuardVariant");
		homePos = tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : null;
		workPos = tag.contains("Work") ? BlockPos.of(tag.getLong("Work")) : null;
		shopPos = tag.contains("Shop") ? BlockPos.of(tag.getLong("Shop")) : null;
		carried.clear();
		ListTag carriedTag = tag.getList("Carried", 10);
		for (int i = 0; i < carriedTag.size(); i++) {
			CompoundTag item = carriedTag.getCompound(i);
			carried.put(item.getString("Id"), item.getInt("Count"));
		}
		applyEquipment();
		refreshNameTag();
	}

}
