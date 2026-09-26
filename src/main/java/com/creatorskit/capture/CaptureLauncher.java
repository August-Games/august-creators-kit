package com.creatorskit.capture;

import com.creatorskit.CreatorsPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development launcher for headless capture: starts the kit plus the
 * headless capture plugin on the host runtime classpath. Requires
 * {@code -ea} (the host only admits builtin plugins with assertions
 * enabled) and whatever launch flags the host needs, e.g.
 * {@code --developer-mode}.
 */
public class CaptureLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CreatorsPlugin.class, HeadlessCapturePlugin.class);
		RuneLite.main(args);
	}
}
