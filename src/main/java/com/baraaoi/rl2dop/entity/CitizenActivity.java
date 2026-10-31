package com.baraaoi.rl2dop.entity;

import java.util.Locale;

/**
 * Занятие жителя в текущий момент дня. Похоже на ванильные {@code Activity} жителей
 * (IDLE / WORK / MEET / REST), но у нас ещё есть ожидание работы и строительство.
 */
public enum CitizenActivity {
	/** Проснулся, вышел из дома. */
	WAKE("wake"),
	/** На своём рабочем месте (ферма, шахта, лесопилка, мастерская, лавка). */
	WORK("work"),
	/** Перерыв: сошлись у таверны, едят и болтают. */
	BREAK("break"),
	/** Вечерняя сходка у колокола — как «meet» у ванильных жителей. */
	MEET("meet"),
	/** Идёт домой спать. */
	REST("rest"),
	SLEEP("sleep"),
	/** Безработный: живёт в таверне и ждёт вакансию. */
	WAIT_JOB("wait_job"),
	/** Строит назначенный поселением дом. */
	BUILDING("building"),
	/** Глава поселения у табличек. */
	POST("post");

	private final String id;

	CitizenActivity(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "rl2dop.activity." + id;
	}

	public static CitizenActivity byId(String id) {
		if (id != null) {
			String normalized = id.toLowerCase(Locale.ROOT);
			for (CitizenActivity activity : values()) {
				if (activity.id.equals(normalized)) {
					return activity;
				}
			}
		}
		return WORK;
	}
}
