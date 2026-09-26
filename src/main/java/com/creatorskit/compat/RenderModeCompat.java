package com.creatorskit.compat;

import net.runelite.api.Actor;
import net.runelite.api.Renderable;

/**
 * Render-mode reads on RuneLite versions where the accessor is missing.
 *
 * <p>On the pinned client, an actor-attached effect is merged into the
 * owning actor's model and never appears in the scene graphics-object deque,
 * so matching a world graphic by effect id cannot recover the mode the
 * effect was rendered with. The mode is read from the owning actor instead,
 * which is the value the scene used for the attached effect.
 */
public final class RenderModeCompat
{
	private RenderModeCompat()
	{
	}

	/**
	 * The render mode the scene uses for effects attached to the given
	 * actor: the actor's own mode. Falls back to
	 * {@link Renderable#RENDERMODE_DEFAULT} for a null actor.
	 */
	public static int getSpotAnimRenderMode(Actor actor)
	{
		if (actor == null)
		{
			return Renderable.RENDERMODE_DEFAULT;
		}

		return actor.getRenderMode();
	}
}
