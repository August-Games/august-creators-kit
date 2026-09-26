package com.creatorskit.capture;

import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SceneResolverTest
{
	private static CustomModelComp comp(CustomModelType type)
	{
		return new CustomModelComp(type, 0, 128, 128, null, null,
			null, null, null, null, false, "test");
	}

	@Test
	public void legacyCompWithoutRequestDoesNotBlock()
	{
		CustomModelComp c = comp(CustomModelType.CACHE_OBJECT);
		assertFalse(c.isResolveRequested());
		assertTrue(SceneResolver.unresolvedIndices(new CustomModelComp[]{c}).isEmpty());
		assertFalse(SceneResolver.blocksPlayback(c));
	}

	@Test
	public void playerWithoutGeometryBlocks()
	{
		CustomModelComp c = comp(CustomModelType.CACHE_PLAYER);
		assertTrue(SceneResolver.blocksPlayback(c));
	}

	@Test
	public void snakeCaseRequestDeserializes()
	{
		String json = "{"
			+ "\"type\":\"CACHE_PLAYER\",\"modelId\":0,"
			+ "\"needs_resolve\":true,\"resolve_type\":\"CACHE_PLAYER\","
			+ "\"equipment_slots\":{\"head\":1163,\"main_hand\":1333},"
			+ "\"kitRecolours\":[2,26,9,0,0],"
			+ "\"female\":false,\"gender\":\"male\","
			+ "\"priority\":false,\"name\":\"Hero\"}";
		CustomModelComp c = new Gson().fromJson(json, CustomModelComp.class);
		assertTrue(c.isResolveRequested());
		assertEquals("CACHE_PLAYER", c.getResolveType());
		assertEquals(Integer.valueOf(1163), c.getEquipmentSlots().get("head"));
		assertEquals(Integer.valueOf(1333), c.getEquipmentSlots().get("main_hand"));
		assertEquals(1, SceneResolver.unresolvedIndices(new CustomModelComp[]{c}).size());
		assertTrue(SceneResolver.blocksPlayback(c));
	}

	@Test
	public void npcRequestDeserializes()
	{
		String json = "{"
			+ "\"type\":\"CACHE_NPC\",\"modelId\":0,"
			+ "\"needs_resolve\":true,\"resolve_type\":\"CACHE_NPC\","
			+ "\"npc_id\":3028,\"priority\":false,\"name\":\"Goblin\"}";
		CustomModelComp c = new Gson().fromJson(json, CustomModelComp.class);
		assertTrue(c.isResolveRequested());
		assertEquals(Integer.valueOf(3028), c.getNpcId());
		assertTrue(SceneResolver.describeBlocker(0, c).contains("3028"));
	}

	@Test
	public void slotMappingCoversCompilerNames()
	{
		assertEquals(Integer.valueOf(0), SceneResolver.slotToKitIndex("head"));
		assertEquals(Integer.valueOf(1), SceneResolver.slotToKitIndex("cape"));
		assertEquals(Integer.valueOf(2), SceneResolver.slotToKitIndex("neck"));
		assertEquals(Integer.valueOf(3), SceneResolver.slotToKitIndex("main_hand"));
		assertEquals(Integer.valueOf(4), SceneResolver.slotToKitIndex("chest"));
		assertEquals(Integer.valueOf(5), SceneResolver.slotToKitIndex("off_hand"));
		assertEquals(Integer.valueOf(7), SceneResolver.slotToKitIndex("legs"));
		assertEquals(Integer.valueOf(9), SceneResolver.slotToKitIndex("hands"));
		assertEquals(Integer.valueOf(10), SceneResolver.slotToKitIndex("feet"));
	}

	@Test
	public void nonVisualSlotsAreSkipped()
	{
		assertNull(SceneResolver.slotToKitIndex("ring"));
		assertNull(SceneResolver.slotToKitIndex("ammo"));
		assertNull(SceneResolver.slotToKitIndex("cape_of_many_things"));
		assertNull(SceneResolver.slotToKitIndex(null));
	}

	@Test
	public void stageTilePreservesRelativeLayout()
	{
		int[] anchor = {3226, 3230, 0};
		int[] player = {3226, 3230, 0};
		assertArrayEquals(new int[]{3230, 3230, 0}, SceneResolver.stageTile(
			new int[]{3228, 3230, 0}, anchor, player, 2, 0, 0));
		assertArrayEquals(new int[]{3228, 3230, 0}, SceneResolver.stageTile(
			anchor, anchor, player, 2, 0, 0));
		// the player plane wins when authored and anchor agree
		assertArrayEquals(new int[]{10, 12, 2}, SceneResolver.stageTile(
			new int[]{5, 5, 1}, new int[]{5, 5, 1}, new int[]{8, 12, 2}, 2, 0, 0));
	}

	@Test
	public void equipmentArrayMergesOntoBase()
	{
		int[] base = new int[12];
		Map<String, Integer> slots = new HashMap<>();
		slots.put("head", 1163);
		slots.put("main_hand", 1333);
		slots.put("ring", 1635);
		int[] merged = SceneResolver.buildEquipmentArray(base, slots, SceneResolver.ITEM_OFFSET);
		assertEquals(512 + 1163, merged[0]);
		assertEquals(512 + 1333, merged[3]);
		assertEquals(0, merged[1]);
		// base is not mutated
		assertEquals(0, base[0]);
	}

	@Test
	public void emptyGeometryBlocksPlayerAndNpc()
	{
		CustomModelComp player = comp(CustomModelType.CACHE_PLAYER);
		player.setModelStats(new com.creatorskit.models.ModelStats[0]);
		assertTrue(SceneResolver.blocksPlayback(player));
		CustomModelComp npc = comp(CustomModelType.CACHE_NPC);
		npc.setModelStats(new com.creatorskit.models.ModelStats[0]);
		assertTrue(SceneResolver.blocksPlayback(npc));
		// non-player comps without geometry stay playable
		assertFalse(SceneResolver.blocksPlayback(comp(CustomModelType.CACHE_OBJECT)));
	}

	@Test
	public void unknownSlotsAreListed()
	{
		Map<String, Integer> slots = new HashMap<>();
		slots.put("head", 1163);
		slots.put("wing", 2);
		slots.put("ring", 1635);
		slots.put("ammo", 5);
		assertEquals(java.util.Collections.singletonList("wing"),
			SceneResolver.badEquipmentSlots(slots));
		assertTrue(SceneResolver.badEquipmentSlots(null).isEmpty());
	}

	@Test
	public void unknownItemIdsAreListed()
	{
		Map<String, Integer> slots = new HashMap<>();
		slots.put("head", 1163);
		slots.put("main_hand", 999999);
		slots.put("off_hand", 0);
		java.util.Set<Integer> known =
			new java.util.HashSet<>(java.util.Collections.singletonList(1163));
		assertEquals(java.util.Collections.singletonList(999999),
			SceneResolver.unknownItemIds(slots, known));
		// without a database every positive id is unknown
		assertEquals(2, SceneResolver.unknownItemIds(slots, null).size());
		assertTrue(SceneResolver.unknownItemIds(null, known).isEmpty());
	}

	@Test
	public void nestedFoldersAndCameraCountForDuration()
	{
		String json = "{"
			+ "\"version\":\"t\",\"comps\":[],"
			+ "\"masterFolderNode\":{\"folderType\":\"MASTER\",\"name\":\"root\","
			+ "\"characterSaves\":[{\"name\":\"A\"}],"
			+ "\"folderSaves\":[{\"folderType\":\"STANDARD\",\"name\":\"n\","
			+ "\"characterSaves\":[{\"name\":\"B\"},{\"name\":\"C\"}],"
			+ "\"folderSaves\":[]}]},"
			+ "\"saves\":[],"
			+ "\"cameraScriptSaves\":[{\"tick\":20.0}]}";
		com.creatorskit.saves.SetupSave save =
			new Gson().fromJson(json, com.creatorskit.saves.SetupSave.class);
		assertEquals(3, SceneResolver.allCharacterSaves(save).size());
		assertEquals("B", SceneResolver.allCharacterSaves(save).get(1).getName());
		assertEquals(20.0, SceneResolver.maxTick(save), 1e-9);
		assertTrue(SceneResolver.allCharacterSaves(null).isEmpty());
		assertEquals(0.0, SceneResolver.maxTick(null), 1e-9);
	}

	@Test
	public void clearOwnedOutputsKeepsForeignFiles() throws Exception
	{
		java.io.File dir = new java.io.File(
			System.getProperty("java.io.tmpdir"),
			"ck-clear-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		try
		{
			for (String n : new String[]{"frame_00001.png", "capture.json",
				"resolved_scene.json", "DONE", "ERROR", "r1.done", "r2.error",
				"capture.json.tmp", "keep.txt"})
			{
				assertTrue(new java.io.File(dir, n).createNewFile());
			}
			assertTrue(new java.io.File(dir, "sub").mkdir());
			SceneResolver.clearOwnedOutputs(dir);
			assertFalse(new java.io.File(dir, "frame_00001.png").exists());
			assertFalse(new java.io.File(dir, "capture.json").exists());
			assertFalse(new java.io.File(dir, "DONE").exists());
			assertFalse(new java.io.File(dir, "ERROR").exists());
			assertFalse(new java.io.File(dir, "r1.done").exists());
			assertFalse(new java.io.File(dir, "capture.json.tmp").exists());
			assertTrue(new java.io.File(dir, "keep.txt").exists());
			assertTrue(new java.io.File(dir, "sub").exists());
		}
		finally
		{
			for (java.io.File f : dir.listFiles())
			{
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void claimRequestMovesAtomically() throws Exception
	{
		java.io.File dir = new java.io.File(
			System.getProperty("java.io.tmpdir"),
			"ck-claim-" + System.nanoTime());
		java.io.File processing = new java.io.File(dir, "processing");
		assertTrue(dir.mkdirs());
		java.io.File req = new java.io.File(dir, "r1.request.json");
		java.nio.file.Files.write(req.toPath(), "{}".getBytes("UTF-8"));
		java.io.File claimed = SceneResolver.claimRequest(req, processing);
		assertFalse(req.exists());
		assertTrue(claimed.exists());
		assertEquals("r1.request.json", claimed.getName());
		boolean secondFailed = false;
		try
		{
			SceneResolver.claimRequest(req, processing);
		}
		catch (Exception e)
		{
			secondFailed = true;
		}
		assertTrue(secondFailed);
		claimed.delete();
		processing.delete();
		dir.delete();
	}

	@Test
	public void errorResultIsValidJson() throws Exception
	{
		java.io.File dir = new java.io.File(
			System.getProperty("java.io.tmpdir"),
			"ck-err-" + System.nanoTime());
		SceneResolver.writeErrorResult(new Gson(), dir, "s.json", "batch",
			30.0, true, "boom: \"quoted\"");
		com.google.gson.JsonObject root = new Gson().fromJson(
			new java.io.FileReader(new java.io.File(dir, "capture.json")),
			com.google.gson.JsonObject.class);
		assertEquals("error", root.get("status").getAsString());
		assertEquals("boom: \"quoted\"", root.get("error").getAsString());
		assertTrue(new java.io.File(dir, "ERROR").exists());
		new java.io.File(dir, "capture.json").delete();
		new java.io.File(dir, "ERROR").delete();
		dir.delete();
	}
}
