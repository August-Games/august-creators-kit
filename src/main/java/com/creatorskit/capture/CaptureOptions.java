package com.creatorskit.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Headless capture options, driven by {@code ck.capture.*} system properties.
 *
 * <p>See {@code docs/headless-capture.md} for the full property list. This
 * class is intentionally free of client dependencies so it can be unit
 * tested on its own.
 */
public class CaptureOptions
{
	public enum Mode
	{
		BATCH,
		DAEMON,
		STILLS
	}

	/** Scene seconds per game tick. */
	public static final double SEC_PER_TICK = 0.6;

	private static final double DEFAULT_FPS = 30.0;
	private static final double DEFAULT_START_SEC = 0.0;
	private static final double DEFAULT_END_SEC = 5.0;
	private static final long DEFAULT_SETTLE_MS = 500L;
	private static final long DEFAULT_DRAW_TIMEOUT_SEC = 30L;

	public final Mode mode;
	public final String scene;
	public final String out;
	public final double fps;
	public final Double startSec;
	public final Double endSec;
	public final double[] stillTimes;
	public final String requestDir;
	public final boolean cropViewport;
	public final long settleMs;
	public final long drawTimeoutSec;

	private CaptureOptions(
		Mode mode,
		String scene,
		String out,
		double fps,
		Double startSec,
		Double endSec,
		double[] stillTimes,
		String requestDir,
		boolean cropViewport,
		long settleMs,
		long drawTimeoutSec)
	{
		this.mode = mode;
		this.scene = scene;
		this.out = out;
		this.fps = fps;
		this.startSec = startSec;
		this.endSec = endSec;
		this.stillTimes = stillTimes;
		this.requestDir = requestDir;
		this.cropViewport = cropViewport;
		this.settleMs = settleMs;
		this.drawTimeoutSec = drawTimeoutSec;
	}

	/** Reads options from the live system properties. */
	public static CaptureOptions fromSystemProperties()
	{
		return fromProperties(System.getProperties());
	}

	static CaptureOptions fromProperties(Properties props)
	{
		boolean modeExplicit = props.getProperty("ck.capture.mode") != null
			&& !props.getProperty("ck.capture.mode").trim().isEmpty();
		Mode mode = parseMode(get(props, "ck.capture.mode", "batch"));
		String scene = emptyToNull(get(props, "ck.capture.scene", null));
		String out = emptyToNull(get(props, "ck.capture.out", null));
		double fps = parseDouble(get(props, "ck.capture.fps", null), DEFAULT_FPS, "ck.capture.fps");
		Double startSec = parseOptionalDouble(get(props, "ck.capture.start", null), "ck.capture.start");
		Double endSec = parseOptionalDouble(get(props, "ck.capture.end", null), "ck.capture.end");
		double[] stillTimes = parseOptionalDoubles(get(props, "ck.capture.times", null), "ck.capture.times");
		String requestDir = emptyToNull(get(props, "ck.capture.requestDir", null));
		boolean cropViewport = Boolean.parseBoolean(get(props, "ck.capture.cropViewport", "false"));
		long settleMs = (long) parseDouble(get(props, "ck.capture.settleMs", null), DEFAULT_SETTLE_MS, "ck.capture.settleMs");
		long drawTimeoutSec = (long) parseDouble(get(props, "ck.capture.drawTimeoutSec", null), DEFAULT_DRAW_TIMEOUT_SEC, "ck.capture.drawTimeoutSec");

		CaptureOptions options = new CaptureOptions(mode, scene, out, fps,
			startSec, endSec, stillTimes, requestDir, cropViewport,
			settleMs, drawTimeoutSec);
		// Bare properties (nothing pointing at a scene or request dir, mode
		// untouched) mean interactive use: stay idle instead of erroring.
		options.validate(scene != null || requestDir != null || modeExplicit);
		return options;
	}

	/**
	 * Whether headless capture was requested at all. When neither a scene
	 * nor a daemon request directory is configured the plugin stays idle so
	 * interactive use is unaffected.
	 */
	public boolean isEnabled()
	{
		if (mode == Mode.DAEMON)
		{
			return requestDir != null;
		}
		return scene != null && out != null;
	}

	private void validate(boolean configured)
	{
		if (fps <= 0 || !Double.isFinite(fps))
		{
			throw new IllegalArgumentException("ck.capture.fps must be a positive finite number");
		}
		if (settleMs < 0)
		{
			throw new IllegalArgumentException("ck.capture.settleMs must be >= 0");
		}
		if (drawTimeoutSec <= 0)
		{
			throw new IllegalArgumentException("ck.capture.drawTimeoutSec must be positive");
		}
		if (!configured)
		{
			return;
		}
		switch (mode)
		{
			case BATCH:
				require(scene != null, "ck.capture.scene is required in batch mode");
				require(out != null, "ck.capture.out is required in batch mode");
				break;
			case STILLS:
				require(scene != null, "ck.capture.scene is required in stills mode");
				require(out != null, "ck.capture.out is required in stills mode");
				require(stillTimes != null && stillTimes.length > 0,
					"ck.capture.times is required in stills mode (comma-separated scene seconds)");
				break;
			case DAEMON:
				require(requestDir != null, "ck.capture.requestDir is required in daemon mode");
				break;
		}
		if (startSec != null && !Double.isFinite(startSec))
		{
			throw new IllegalArgumentException("ck.capture.start must be finite");
		}
		if (endSec != null && !Double.isFinite(endSec))
		{
			throw new IllegalArgumentException("ck.capture.end must be finite");
		}
		if (startSec != null && endSec != null && endSec < startSec)
		{
			throw new IllegalArgumentException("ck.capture.end must be >= ck.capture.start");
		}
		if (stillTimes != null)
		{
			for (double t : stillTimes)
			{
				if (!Double.isFinite(t) || t < 0)
				{
					throw new IllegalArgumentException("ck.capture.times must be finite and >= 0");
				}
			}
		}
	}

	/**
	 * Frame times in scene seconds for a batch range. The end is exclusive:
	 * a 5 s range at 30 fps yields 150 frames.
	 */
	public static double[] frameTimesForRange(double startSec, double endSec, double fps)
	{
		if (!(fps > 0) || !Double.isFinite(fps))
		{
			throw new IllegalArgumentException("fps must be a positive finite number");
		}
		if (endSec < startSec)
		{
			throw new IllegalArgumentException("endSec must be >= startSec");
		}
		int n = (int) Math.round((endSec - startSec) * fps);
		double[] times = new double[n];
		for (int i = 0; i < n; i++)
		{
			times[i] = startSec + i / fps;
		}
		return times;
	}

	/** Scene seconds to kit ticks. */
	public static double secToTick(double sec)
	{
		return sec / SEC_PER_TICK;
	}

	public double effectiveStartSec()
	{
		return startSec == null ? DEFAULT_START_SEC : startSec;
	}

	public double effectiveEndSec(double sceneMaxSec)
	{
		if (endSec != null)
		{
			return endSec;
		}
		if (Double.isFinite(sceneMaxSec) && sceneMaxSec > effectiveStartSec())
		{
			return sceneMaxSec;
		}
		return effectiveStartSec() + DEFAULT_END_SEC;
	}

	private static Mode parseMode(String raw)
	{
		String v = raw == null ? "batch" : raw.trim().toLowerCase();
		switch (v)
		{
			case "batch":
				return Mode.BATCH;
			case "daemon":
				return Mode.DAEMON;
			case "stills":
				return Mode.STILLS;
			default:
				throw new IllegalArgumentException(
					"ck.capture.mode must be one of batch, daemon, stills");
		}
	}

	private static String get(Properties props, String key, String def)
	{
		String v = props.getProperty(key);
		return v == null ? def : v;
	}

	private static String emptyToNull(String v)
	{
		if (v == null || v.trim().isEmpty())
		{
			return null;
		}
		return v.trim();
	}

	private static double parseDouble(String raw, double def, String key)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return def;
		}
		try
		{
			return Double.parseDouble(raw.trim());
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(key + " must be a number", e);
		}
	}

	private static Double parseOptionalDouble(String raw, String key)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Double.parseDouble(raw.trim());
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(key + " must be a number", e);
		}
	}

	private static double[] parseOptionalDoubles(String raw, String key)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		String[] parts = raw.split(",");
		List<Double> out = new ArrayList<>();
		for (String part : parts)
		{
			String p = part.trim();
			if (p.isEmpty())
			{
				continue;
			}
			try
			{
				out.add(Double.parseDouble(p));
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException(key + " must be a comma-separated list of numbers", e);
			}
		}
		double[] arr = new double[out.size()];
		for (int i = 0; i < out.size(); i++)
		{
			arr[i] = out.get(i);
		}
		return arr;
	}

	private static void require(boolean cond, String message)
	{
		if (!cond)
		{
			throw new IllegalArgumentException(message);
		}
	}
}
