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
}
