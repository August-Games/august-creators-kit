package com.creatorskit.capture;

import com.creatorskit.saves.CharacterSave;
import com.creatorskit.saves.ModelKeyFrameSave;
import com.google.gson.Gson;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * NPC footprint centring (013): a size-N NPC's model sits (N-1)/2 tiles NE
 * of its SW anchor. The save carries whole tiles, the live character the
 * exact local-unit remainder.
 */
public class NpcSizingTest
{
	private static final Gson GSON = new Gson();

	private static CharacterSave npcSave()
	{
		String json = "{"
			+ "\"id\":\"x\",\"name\":\"Vorkath\","
			+ "\"nonInstancedPoint\":{\"x\":10,\"y\":20,\"plane\":0},"
			+ "\"compId\":0,\"radius\":1,"
			+ "\"movementKeyFrames\":[{\"tick\":0.0,\"plane\":0,\"poh\":false,"
			+ "\"path\":[[10,20],[11,21]],\"currentStep\":0,\"stepClientTick\":0,"
			+ "\"loop\":false,\"speed\":0.0,\"turnRate\":0}],"
			+ "\"modelKeyFrameSaves\":[{\"tick\":0.0,\"useCustomModel\":false,"
			+ "\"modelId\":1,\"customModel\":0,\"radius\":1}]"
			+ "}";
		return GSON.fromJson(json, CharacterSave.class);
	}

	@Test
	public void shiftIsFloorOfHalfFootprint()
	{
		assertEquals(0, HeadlessCapturePlugin.npcCentreShiftTiles(1));
		assertEquals(0, HeadlessCapturePlugin.npcCentreShiftTiles(2));
		assertEquals(1, HeadlessCapturePlugin.npcCentreShiftTiles(3));
		assertEquals(1, HeadlessCapturePlugin.npcCentreShiftTiles(4));
		assertEquals(3, HeadlessCapturePlugin.npcCentreShiftTiles(7));
	}

	@Test
	public void remainderIsExactToLocalUnit()
	{
		assertEquals(0, HeadlessCapturePlugin.npcCentreRemainderLocal(1));
		assertEquals(64, HeadlessCapturePlugin.npcCentreRemainderLocal(2));
		assertEquals(0, HeadlessCapturePlugin.npcCentreRemainderLocal(3));
		assertEquals(64, HeadlessCapturePlugin.npcCentreRemainderLocal(4));
		assertEquals(0, HeadlessCapturePlugin.npcCentreRemainderLocal(7));
	}

	@Test
	public void shiftPlusRemainderIsExactForAllSizes()
	{
		for (int size = 1; size <= 8; size++)
		{
			int total = HeadlessCapturePlugin.npcCentreShiftTiles(size) * 128
				+ HeadlessCapturePlugin.npcCentreRemainderLocal(size);
			assertEquals("size " + size, (size - 1) * 64, total);
		}
	}

	@Test
	public void sizeSevenShiftsAnchorStampRadius()
	{
		CharacterSave ch = npcSave();
		HeadlessCapturePlugin.applyNpcSize(ch, 7);
		assertEquals(7, ch.getRadius());
		WorldPoint p = ch.getNonInstancedPoint();
		assertEquals(13, p.getX());
		assertEquals(23, p.getY());
		int[][] path = ch.getMovementKeyFrames()[0].getPath();
		assertEquals(13, path[0][0]);
		assertEquals(23, path[0][1]);
		assertEquals(14, path[1][0]);
		assertEquals(24, path[1][1]);
		for (ModelKeyFrameSave kf : ch.getModelKeyFrameSaves())
		{
			assertEquals(7, kf.getRadius());
		}
	}

	@Test
	public void sizeTwoStampsRadiusWithoutTileShift()
	{
		// The half tile rides the live character remainder, not the save.
		CharacterSave ch = npcSave();
		HeadlessCapturePlugin.applyNpcSize(ch, 2);
		assertEquals(2, ch.getRadius());
		assertEquals(10, ch.getNonInstancedPoint().getX());
		assertEquals(10, ch.getMovementKeyFrames()[0].getPath()[0][0]);
	}

	@Test
	public void nullSavesSurvive()
	{
		CharacterSave ch = npcSave();
		ch.setMovementKeyFrames(null);
		ch.setModelKeyFrameSaves(null);
		ch.setNonInstancedPoint(null);
		HeadlessCapturePlugin.applyNpcSize(ch, 7);
		assertEquals(7, ch.getRadius());
		assertNull(ch.getNonInstancedPoint());
	}
}
