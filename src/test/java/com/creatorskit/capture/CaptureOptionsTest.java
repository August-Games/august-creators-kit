package com.creatorskit.capture;

import java.util.Properties;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CaptureOptionsTest
{
	private static Properties props(String... kv)
	{
		Properties p = new Properties();
		for (int i = 0; i < kv.length; i += 2)
		{
			p.setProperty(kv[i], kv[i + 1]);
		}
		return p;
	}

	@Test
	public void batchRangeYieldsExclusiveEndFrames()
	{
		assertEquals(150, CaptureOptions.frameTimesForRange(0.0, 5.0, 30.0).length);
		double[] times = CaptureOptions.frameTimesForRange(0.0, 1.0, 30.0);
		assertEquals(30, times.length);
		assertEquals(0.0, times[0], 1e-9);
		assertEquals(29.0 / 30.0, times[29], 1e-9);
	}

	@Test
	public void secToTickUsesPointSix()
	{
		assertEquals(5.0, CaptureOptions.secToTick(3.0), 1e-9);
		assertEquals(0.0, CaptureOptions.secToTick(0.0), 1e-9);
	}

	@Test
	public void stillsRequiresTimes()
	{
		try
		{
			CaptureOptions.fromProperties(props(
				"ck.capture.mode", "stills",
				"ck.capture.scene", "s.json",
				"ck.capture.out", "/tmp/o"));
			fail("expected refusal without times");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("ck.capture.times"));
		}
	}

	@Test
	public void daemonRequiresRequestDir()
	{
		try
		{
			CaptureOptions.fromProperties(props("ck.capture.mode", "daemon"));
			fail("expected refusal without requestDir");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("ck.capture.requestDir"));
		}
	}

	@Test
	public void batchRequiresSceneAndOut()
	{
		try
		{
			CaptureOptions.fromProperties(props("ck.capture.scene", "s.json"));
			fail("expected refusal without out");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("ck.capture.out"));
		}
	}

	@Test
	public void unknownModeRefused()
	{
		try
		{
			CaptureOptions.fromProperties(props("ck.capture.mode", "stream"));
			fail("expected refusal of unknown mode");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("ck.capture.mode"));
		}
	}

	@Test
	public void endBeforeStartRefused()
	{
		try
		{
			CaptureOptions.fromProperties(props(
				"ck.capture.scene", "s.json",
				"ck.capture.out", "/tmp/o",
				"ck.capture.start", "4",
				"ck.capture.end", "2"));
			fail("expected refusal of inverted range");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("ck.capture.end"));
		}
	}

	@Test
	public void stillTimesParsed()
	{
		CaptureOptions o = CaptureOptions.fromProperties(props(
			"ck.capture.mode", "stills",
			"ck.capture.scene", "s.json",
			"ck.capture.out", "/tmp/o",
			"ck.capture.times", "0, 2.5,5"));
		assertArrayEquals(new double[]{0.0, 2.5, 5.0}, o.stillTimes, 1e-9);
	}

	@Test
	public void idleWhenUnconfigured()
	{
		assertFalse(CaptureOptions.fromProperties(new Properties()).isEnabled());
		assertTrue(CaptureOptions.fromProperties(props(
			"ck.capture.scene", "s.json",
			"ck.capture.out", "/tmp/o")).isEnabled());
	}

	@Test
	public void fpsDefaultsToThirty()
	{
		CaptureOptions o = CaptureOptions.fromProperties(props(
			"ck.capture.scene", "s.json",
			"ck.capture.out", "/tmp/o"));
		assertEquals(30.0, o.fps, 1e-9);
		assertFalse(o.cropViewport);
	}

	@Test
	public void fractionalRangesKeepFinalFrame()
	{
		double[] two = CaptureOptions.frameTimesForRange(0.0, 0.04, 30.0);
		assertEquals(2, two.length);
		assertEquals(0.0, two[0], 1e-9);
		assertEquals(1.0 / 30.0, two[1], 1e-9);
		assertEquals(1, CaptureOptions.frameTimesForRange(0.0, 0.01, 30.0).length);
		assertEquals(150, CaptureOptions.frameTimesForRange(0.0, 5.0, 30.0).length);
		assertEquals(0, CaptureOptions.frameTimesForRange(2.0, 2.0, 30.0).length);
	}

	@Test
	public void framingOptionsArePerJob()
	{
		CaptureOptions o = CaptureOptions.fromProperties(props(
			"ck.capture.scene", "s.json",
			"ck.capture.out", "/tmp/o",
			"ck.capture.stageOnPlayer", "false",
			"ck.capture.stageOffset", "1,0",
			"ck.capture.aimCamera", "false",
			"ck.capture.pitch", "300",
			"ck.capture.zoom", "64.0",
			"ck.capture.canvas", "1540x900"));
		assertFalse(o.stageOnPlayer);
		assertEquals("1,0", o.stageOffset);
		assertFalse(o.aimCamera);
		assertEquals(300, o.pitch);
		assertEquals(64, o.zoom);
		assertEquals("1540x900", o.canvas);
	}

	@Test
	public void framingDefaultsHold()
	{
		CaptureOptions o = CaptureOptions.fromProperties(props(
			"ck.capture.scene", "s.json",
			"ck.capture.out", "/tmp/o"));
		assertTrue(o.stageOnPlayer);
		assertEquals("2,0", o.stageOffset);
		assertTrue(o.aimCamera);
		assertEquals(335, o.pitch);
		assertEquals(-1, o.zoom);
		assertEquals(null, o.canvas);
	}
}
