package com.baraaoi.rl2dop.entity;

import java.util.Locale;

/** Простые состояния NPC: стоять, идти, работать, есть, спать, паниковать, атаковать. */
public enum CitizenState {
	IDLE("idle"),
	WALKING("walking"),
	WORKING("working"),
	EATING("eating"),
	SLEEPING("sleeping"),
	PANIC("panic"),
	ATTACK("attack"),
	SHOPPING("shopping");

	private final String id;

	CitizenState(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "rl2dop.state." + id;
	}

	public static CitizenState byId(String id) {
		if (id != null) {
			String normalized = id.toLowerCase(Locale.ROOT);
			for (CitizenState state : values()) {
				if (state.id.equals(normalized)) {
					return state;
				}
			}
		}
		return IDLE;
	}
}
