package com.creatorskit.capture;

import net.runelite.api.CollisionDataFlag;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Capture-camera clearance guard (pv2 021 fix 6): the converged camera
 * must not sit inside a wall/object tile or below the terrain, or the
 * frame renders void. Pure flag/height predicates; live data is read
 * in HeadlessCapturePlugin.checkCameraClear.
 */
public class CameraGuardTest
{
	@Test
	public void openTilePasses()
	{
		assertFalse(HeadlessCapturePlugin.cameraTileBlocked(0));
	}

	@Test
	public void wallAndSightBlockersFail()
	{
		assertTrue(HeadlessCapturePlugin.cameraTileBlocked(
			CollisionDataFlag.BLOCK_MOVEMENT_FULL));
		assertTrue(HeadlessCapturePlugin.cameraTileBlocked(
			CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL));
		assertTrue(HeadlessCapturePlugin.cameraTileBlocked(
			CollisionDataFlag.BLOCK_MOVEMENT_OBJECT));
	}

	@Test
	public void walkableDirectionalFlagsPass()
	{
		// One-way movement blocks are walkable ground, not walls.
		assertFalse(HeadlessCapturePlugin.cameraTileBlocked(
			CollisionDataFlag.BLOCK_MOVEMENT_NORTH));
	}

	@Test
	public void cameraAboveTerrainPasses()
	{
		// Height-down units: smaller camera value is a higher camera.
		assertFalse(HeadlessCapturePlugin.cameraBelowTerrain(100, 400));
		assertFalse(HeadlessCapturePlugin.cameraBelowTerrain(400, 400));
	}

	@Test
	public void cameraBelowTerrainFails()
	{
		assertTrue(HeadlessCapturePlugin.cameraBelowTerrain(500, 400));
	}

	@Test
	public void swathCoversEndpointsAndNeighbours()
	{
		java.util.List<int[]> swath = HeadlessCapturePlugin.swathTiles(50, 50, 56, 50, 0.27);
		boolean start = false;
		boolean end = false;
		boolean besideFar = false;
		boolean besideNear = false;
		for (int[] t : swath)
		{
			if (t[0] == 50 && t[1] == 50)
			{
				start = true;
			}
			if (t[0] == 56 && t[1] == 50)
			{
				end = true;
			}
			if (t[0] == 55 && t[1] == 51)
			{
				besideFar = true;
			}
			if (t[0] == 51 && t[1] == 51)
			{
				besideNear = true;
			}
		}
		assertTrue(start);
		assertTrue(end);
		assertTrue(besideFar);
		// Frustum narrows to the lens: one tile out the ring has
		// not opened yet (neighbour centres reach (53,51) via union,
		// but nothing reaches (51,51)).
		assertTrue(!besideNear);
	}

	@Test
	public void swathDegenerateIsCentreOnly()
	{
		java.util.List<int[]> swath = HeadlessCapturePlugin.swathTiles(7, 7, 7, 7, 0.27);
		assertTrue(swath.size() == 1);
	}

	@Test
	public void swathExcludesOffAxisNearCameraTile()
	{
		// Home-corner regression: the tree one tile out from the lens
		// sits off the sight line and never renders, while an on-axis
		// tile five tiles out stays covered.
		java.util.List<int[]> swath = HeadlessCapturePlugin.swathTiles(63, 55, 71, 58, 0.266);
		boolean nearOffAxis = false;
		boolean farOnAxis = false;
		for (int[] t : swath)
		{
			if (t[0] == 64 && t[1] == 56)
			{
				nearOffAxis = true;
			}
			if (t[0] == 67 && t[1] == 57)
			{
				farOnAxis = true;
			}
		}
		assertTrue(!nearOffAxis);
		assertTrue(farOnAxis);
	}

	@Test
	public void sightInterpolatesEndpoints()
	{
		// Camera high (down 100), focal lower (down 500): endpoints exact.
		assertTrue(HeadlessCapturePlugin.sightHeightDown(
			0, 0, 100, 1280, 0, 500, 0, 0) == 100);
		assertTrue(HeadlessCapturePlugin.sightHeightDown(
			0, 0, 100, 1280, 0, 500, 1280, 0) == 500);
	}

	@Test
	public void sightMidpointAndClamp()
	{
		assertTrue(HeadlessCapturePlugin.sightHeightDown(
			0, 0, 100, 1280, 0, 500, 640, 0) == 300);
		// Past the focal clamps to the focal end.
		assertTrue(HeadlessCapturePlugin.sightHeightDown(
			0, 0, 100, 1280, 0, 500, 2560, 0) == 500);
	}

	@Test
	public void pierceNeedsClearMargin()
	{
		// Down units: smaller top is taller. 63 above the line passes,
		// 65 above fails (64 margin).
		assertFalse(HeadlessCapturePlugin.piercesSight(436, 500));
		assertTrue(HeadlessCapturePlugin.piercesSight(435, 500));
		assertFalse(HeadlessCapturePlugin.piercesSight(600, 500));
	}

	@Test
	public void objectTopDownSubtractsHeight()
	{
		assertTrue(HeadlessCapturePlugin.objectTopDown(-896, 240) == -1136);
	}

	@Test
	public void maxModelHeightNullSafe()
	{
		assertTrue(HeadlessCapturePlugin.maxModelHeight() == 0);
		assertTrue(HeadlessCapturePlugin.maxModelHeight(null, null) == 0);
	}

	@Test
	public void kitOwnedObjectsSkipCone()
	{
		assertTrue(HeadlessCapturePlugin.skipConeObject(-1));
		assertFalse(HeadlessCapturePlugin.skipConeObject(0));
		assertFalse(HeadlessCapturePlugin.skipConeObject(218));
	}
}
