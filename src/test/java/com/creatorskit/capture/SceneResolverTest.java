package com.creatorskit.capture;

import com.creatorskit.models.CustomModelComp;
import com.creatorskit.models.CustomModelType;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

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
}
