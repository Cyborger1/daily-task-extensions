/*
 * Copyright (c) 2020, Cyborger1
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.dailytaskextensions;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.RuneLiteAPI;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@PluginDescriptor(
	name = "Daily Chronicle",
	description = "Reminds you to buy teleport cards from Diango upon login",
	tags = {"daily", "task", "indicator", "chronicle", "teleport", "card"}
)
public class DailyTaskExtensionsPlugin extends Plugin
{
	private static final int ONE_DAY = 86400000;

	private static final String CHRONICLE_STORE_NAME = "Diango's Toy Store.";
	private static final String CHRONICLE_MESSAGE = "You have %d chronicle teleport cards waiting to be bought from Diango.";
	private static final int TELEPORT_CARDS_MAX = 100;
	private static final String CHRONICLE_MAXED_CHAT = "You can only buy " + TELEPORT_CARDS_MAX + " of those per day.";

	@Inject
	private Client client;

	@Inject
	private DailyTaskExtensionsConfig config;

	@Provides
	DailyTaskExtensionsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DailyTaskExtensionsConfig.class);
	}

	@Inject
	private ChatMessageManager chatMessageManager;

	private long lastReset;
	private boolean loggingIn;
	private boolean isPastDailyReset;

	private Map<String, ChronicleCardCount> chronicleMap;
	private boolean inChronicleShop;
	private int lastCardCount;

	@Override
	protected void startUp() throws Exception
	{
		loggingIn = true;
		setChronicleMapFromConfig();
		inChronicleShop = false;
		lastCardCount = 0;
		isPastDailyReset = false;
	}

	@Override
	protected void shutDown() throws Exception
	{
		lastReset = 0L;
		chronicleMap = null;
		inChronicleShop = false;
		lastCardCount = 0;
		isPastDailyReset = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			loggingIn = true;
			if (state == GameState.HOPPING)
			{
				isPastDailyReset = false;
				inChronicleShop = false;
				lastCardCount = 0;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Look if the player is still in the shop
		processChronicleShop();

		long currentTime = System.currentTimeMillis();
		boolean dailyReset = !loggingIn && currentTime - lastReset > ONE_DAY;

		if (dailyReset)
		{
			// Required for chronicle cards to stop updating the chronicle map until the player relogs
			// This will cause issues if the player turns the plugin off and on in this state
			isPastDailyReset = true;
		}

		if (dailyReset || loggingIn)
		{
			// Round down to the nearest day
			lastReset = currentTime - (currentTime % ONE_DAY);
			loggingIn = false;

			if (config.showChronicle())
			{
				checkChronicle();
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// shop_main_init - Loads values into the shop widget
		if (event.getScriptId() == 1074)
		{
			// Check if shop widget is Diango's store and set the chronicle if it is
			Widget shop = client.getWidget(WidgetID.SHOP_GROUP_ID, 1);
			if (shop != null)
			{
				Widget shopTitle = shop.getChild(1);
				if (shopTitle != null && shopTitle.getText().contains(CHRONICLE_STORE_NAME))
				{
					inChronicleShop = true;
					// Initial card count when entering the shop
					lastCardCount = countTeleportCardsInInventory();
				}
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Update the chronicle card count for the current user if the number of cards in inventory increased.
		if (inChronicleShop && !isPastDailyReset && event.getContainerId() == InventoryID.INVENTORY.getId())
		{
			updateChronicleCardsForCurrentUser();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (inChronicleShop && !isPastDailyReset
			&& event.getType() == ChatMessageType.GAMEMESSAGE
			&& event.getMessage().equals(CHRONICLE_MAXED_CHAT))
		{
			if (getCardCountForCurrentUser().getCardsBought() < TELEPORT_CARDS_MAX)
			{
				setCardCountForCurrentUser(TELEPORT_CARDS_MAX);
			}
		}
	}

	private void checkChronicle()
	{
		// Getter automatically resets to 0 if a day has passed
		final ChronicleCardCount cardCount = getCardCountForCurrentUser(false);
		final int cardsLeft = TELEPORT_CARDS_MAX -
			(cardCount != null ? cardCount.getCardsBought() : 0);
		if (cardsLeft > 0)
		{
			sendChatMessage(String.format(CHRONICLE_MESSAGE, cardsLeft));
		}
	}

	private void sendChatMessage(String chatMessage)
	{
		final String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(chatMessage)
			.build();

		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
	}

	/**
	 * Sets the instance of the chronicle map from the hidden config field.
	 */
	private void setChronicleMapFromConfig()
	{
		final InputStream in = new ByteArrayInputStream(config.getChronicleBoughtJSON().getBytes());
		final Type typeToken = new TypeToken<Map<String, ChronicleCardCount>>()
		{
		}.getType();
		try
		{
			chronicleMap = RuneLiteAPI.GSON.fromJson(new InputStreamReader(in), typeToken);
		}
		catch (JsonParseException ex)
		{
			chronicleMap = new HashMap<>();
		}
	}

	/**
	 * Puts the contents of the chronicle map in the hidden config field.
	 */
	private void putChronicleMapInConfig()
	{
		config.setChronicleBoughtJSON(RuneLiteAPI.GSON.toJson(chronicleMap));
	}

	/**
	 * Sets the number of cards bought for the current user.
	 *
	 * @param cards The number of cards.
	 * @return The saved card count object for the current user.
	 */
	private ChronicleCardCount setCardCountForCurrentUser(int cards)
	{
		final ChronicleCardCount cardCount =
			ChronicleCardCount.builder()
				.cardsBought(cards)
				.lastReset((int) (lastReset / ONE_DAY)).build();
		chronicleMap.put(client.getUsername(), cardCount);
		putChronicleMapInConfig();
		return cardCount;
	}

	/**
	 * Adds a number of cards to the card count object for the current user.
	 *
	 * @param cardsToAdd The number of cards to add.
	 * @return The saved card count object for the current user. Null is the user is already at max for today.
	 */
	private ChronicleCardCount addCardCountForCurrentUser(int cardsToAdd)
	{
		final int oldCount = getCardCountForCurrentUser().getCardsBought();
		if (oldCount < TELEPORT_CARDS_MAX)
		{
			return setCardCountForCurrentUser(oldCount + cardsToAdd);
		}
		else
		{
			return null;
		}
	}

	/**
	 * Gets the card count object from the chronicle map for the current user.
	 * <p>
	 * Initializes a new object with 0 cards and adds it to the map if the current user is not found
	 * or if the current user's last card reset was more than a day ago.
	 *
	 * @param setIfMissingOrNewDay If true, create and save a new card object, else return null.
	 * @return The card count object for the current user.
	 */
	private ChronicleCardCount getCardCountForCurrentUser(boolean setIfMissingOrNewDay)
	{
		final ChronicleCardCount cardCount = chronicleMap.get(client.getUsername());
		if (cardCount == null || (System.currentTimeMillis() / ONE_DAY) > cardCount.getLastReset())
		{
			if (!setIfMissingOrNewDay)
			{
				return null;
			}
			return setCardCountForCurrentUser(0);
		}
		return cardCount;
	}

	private ChronicleCardCount getCardCountForCurrentUser()
	{
		return getCardCountForCurrentUser(true);
	}

	/**
	 * Count the number of chronicle teleport cards currently in the player's inventory.
	 *
	 * @return The number of cards.
	 */
	private int countTeleportCardsInInventory()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		return inv != null ? inv.count(ItemID.TELEPORT_CARD) : 0;
	}

	/**
	 * Check if the shop widget is still loaded, remove chronicle flag if it isn't.
	 */
	private void processChronicleShop()
	{
		if (inChronicleShop && client.getWidget(WidgetID.SHOP_GROUP_ID, 1) == null)
		{
			inChronicleShop = false;
		}
	}

	private synchronized void updateChronicleCardsForCurrentUser()
	{
		final int newCardCount = countTeleportCardsInInventory();
		if (newCardCount > lastCardCount)
		{
			addCardCountForCurrentUser(newCardCount - lastCardCount);
		}
		lastCardCount = newCardCount;
	}
}
