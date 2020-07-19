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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import net.runelite.http.api.RuneLiteAPI;

public class UserActionsMap
{
	private Map<String, ActionsPerformedCounter> userActions;
	private final int maxActions;

	@Getter
	@Setter
	private boolean isDirty;

	public UserActionsMap(int maxActions, String json)
	{
		this.maxActions = maxActions;
		final InputStream in = new ByteArrayInputStream(json.getBytes());
		final Type typeToken = new TypeToken<Map<String, ActionsPerformedCounter>>()
		{
		}.getType();
		try
		{
			userActions = RuneLiteAPI.GSON.fromJson(new InputStreamReader(in), typeToken);
		}
		catch (JsonParseException ex)
		{
			userActions = new HashMap<>();
		}
	}

	public UserActionsMap(int maxActions)
	{
		this(maxActions, "{}");
	}

	public String getJSONString()
	{
		return RuneLiteAPI.GSON.toJson(userActions);
	}

	/**
	 * Sets the number of actions performed.
	 *
	 * @param user    The user.
	 * @param today   The current day from Epoch.
	 * @param actions The number of actions.
	 * @return The saved action count object for the give user.
	 */
	public ActionsPerformedCounter setCountForUser(String user, int today, int actions)
	{
		final ActionsPerformedCounter count =
			ActionsPerformedCounter.builder()
				.actionsPerformed(actions)
				.lastDay(today).build();
		userActions.put(user, count);
		isDirty = true;
		return count;
	}

	/**
	 * Adds a number of actions to the action count object for the given user.
	 *
	 * @param user    The user.
	 * @param today   The current day from Epoch.
	 * @param actions The number of actions to add.
	 * @return The saved action count object for the give user.
	 */
	public ActionsPerformedCounter addCountForUser(String user, int today, int actions)
	{
		final ActionsPerformedCounter actionsCounter = getCountForUser(user, today);
		final int oldCount = actionsCounter.getActionsPerformed();
		if (oldCount < maxActions)
		{
			return setCountForUser(user, actions + oldCount, today);
		}
		else
		{
			return actionsCounter;
		}
	}

	/**
	 * Gets the action count object from the user map for the given user.
	 * <p>
	 * Initializes a new object with 0 actions and adds it to the map if the given user is not found
	 * or if the given user's last reset was more than a day ago.
	 *
	 * @param user                 The user.
	 * @param setIfMissingOrNewDay If true, create and save a new action counter object, else return null.
	 * @return The action count object for the current user.
	 */
	public ActionsPerformedCounter getCountForUser(String user, int today, boolean setIfMissingOrNewDay)
	{
		final ActionsPerformedCounter count = userActions.get(user);
		if (count == null || today > count.getLastDay())
		{
			if (!setIfMissingOrNewDay)
			{
				return null;
			}
			return setCountForUser(user, today, 0);
		}
		return count;
	}

	public ActionsPerformedCounter getCountForUser(String user, int today)
	{
		return getCountForUser(user, today, true);
	}
}

