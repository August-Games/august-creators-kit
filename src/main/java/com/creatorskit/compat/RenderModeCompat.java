package com.creatorskit.compat;

import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Renderable;

/**
 * Render-mode reads on RuneLite versions where the accessor is missing.
 *
 * <p>Newer RuneLite exposes the mode on more types. Where the pinned API
 * already carries the value on the live scene object, this class reads it
 * from there, so stored models keep the same mode as on newer clients.
 */
public final class RenderModeCompat
{
	private RenderModeCompat()
	{
	}

	/**
	 * Equivalent of {@code ActorSpotAnim.getRenderMode()}: the mode the
	 * client's scene uses for the live effect, matched by effect id and
	 * start cycle. Falls back to {@link Renderable#RENDERMODE_DEFAULT}
	 * once the effect has left the scene.
	 */
	public static int getSpotAnimRenderMode(Client client, ActorSpotAnim spotAnim)
	{
		if (client == null || spotAnim == null)
		{
			return Renderable.RENDERMODE_DEFAULT;
		}

		try
		{
			for (GraphicsObject graphicsObject : client.getGraphicsObjects())
			{
				if (graphicsObject.getId() == spotAnim.getId()
					&& graphicsObject.getStartCycle() == spotAnim.getStartCycle())
				{
					return graphicsObject.getRenderMode();
				}
			}
		}
		catch (Exception e)
		{
			// Fall through to the default below.
		}
		return Renderable.RENDERMODE_DEFAULT;
	}
}
