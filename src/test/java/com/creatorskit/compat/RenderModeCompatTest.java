package com.creatorskit.compat;

import java.lang.reflect.Proxy;
import net.runelite.api.Actor;
import net.runelite.api.Renderable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RenderModeCompatTest
{
	private static Actor actorWithMode(int mode)
	{
		return (Actor) Proxy.newProxyInstance(
			RenderModeCompatTest.class.getClassLoader(),
			new Class<?>[]{Actor.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getRenderMode"))
				{
					return mode;
				}
				throw new UnsupportedOperationException(method.getName());
			});
	}

	@Test
	public void passesThroughOwningActorMode()
	{
		assertEquals(Renderable.RENDERMODE_SORTED_NO_DEPTH,
			RenderModeCompat.getSpotAnimRenderMode(
				actorWithMode(Renderable.RENDERMODE_SORTED_NO_DEPTH)));
		assertEquals(Renderable.RENDERMODE_DEFAULT,
			RenderModeCompat.getSpotAnimRenderMode(
				actorWithMode(Renderable.RENDERMODE_DEFAULT)));
	}

	@Test
	public void nullActorFallsBackToDefault()
	{
		assertEquals(Renderable.RENDERMODE_DEFAULT,
			RenderModeCompat.getSpotAnimRenderMode(null));
	}
}
