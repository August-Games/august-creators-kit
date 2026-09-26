package com.creatorskit.compat;

import com.creatorskit.models.dataloaders.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * Hand-item overrides for animations on RuneLite versions whose
 * {@code Animation} API has no hand-item getters.
 *
 * <p>Newer RuneLite exposes {@code Animation.getLeftHandItem()} /
 * {@code getRightHandItem()} ("the item id or -1"). Those values are decoded
 * from opcodes 6 and 7 of the cache {@code Sequence} definition, which is
 * unchanged on disk: this class reads the same two unsigned shorts with the
 * same {@code -1} default, so forged player models hide/swap hand items
 * exactly as on newer clients.
 *
 * <p>Sequence archive of the config index, opcode numbers and field semantics
 * match the public RuneLite cache decoder. Any unreadable or unexpected data
 * falls back to {@code -1} (no override), the API's own unknown value.
 */
@Slf4j
public final class AnimationCompat
{
	private static final int SEQUENCE_ARCHIVE = 12;
	private static final int NO_OVERRIDE = -1;

	private AnimationCompat()
	{
	}

	/**
	 * Equivalent of {@code Animation.getLeftHandItem()} for the given animation id.
	 *
	 * @return the override value ({@code -1} unaltered, {@code 0} hide,
	 * otherwise {@code 512 + itemId} to swap), or {@code -1} when unreadable.
	 */
	public static int getLeftHandItem(Client client, int animId)
	{
		return readHandItems(client, animId)[0];
	}

	/**
	 * Equivalent of {@code Animation.getRightHandItem()} for the given animation id.
	 *
	 * @return the override value ({@code -1} unaltered, {@code 0} hide,
	 * otherwise {@code 512 + itemId} to swap), or {@code -1} when unreadable.
	 */
	public static int getRightHandItem(Client client, int animId)
	{
		return readHandItems(client, animId)[1];
	}

	private static int[] readHandItems(Client client, int animId)
	{
		int[] result = new int[]{NO_OVERRIDE, NO_OVERRIDE};
		if (client == null || client.getIndexConfig() == null || animId < 0)
		{
			return result;
		}

		byte[] data;
		try
		{
			data = client.getIndex(2).loadData(SEQUENCE_ARCHIVE, animId);
		}
		catch (Exception e)
		{
			log.debug("Hand-item lookup failed for anim {}", animId, e);
			return result;
		}
		if (data == null || data.length == 0)
		{
			return result;
		}

		try
		{
			InputStream stream = new InputStream(data);
			while (true)
			{
				require(stream, 1);
				int opcode = stream.readUnsignedByte();
				if (opcode == 0)
				{
					break;
				}
				if (!skipOpcode(stream, opcode, result))
				{
					return new int[]{NO_OVERRIDE, NO_OVERRIDE};
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Hand-item decode failed for anim {}", animId, e);
			return new int[]{NO_OVERRIDE, NO_OVERRIDE};
		}
		return result;
	}

	/**
	 * Skips one opcode body, capturing opcodes 6/7. Returns false when the
	 * opcode is unknown or the data runs short, in which case the caller
	 * falls back to no override.
	 */
	private static boolean skipOpcode(InputStream stream, int opcode, int[] result)
	{
		switch (opcode)
		{
			case 1:
			{
				require(stream, 2);
				int count = stream.readUnsignedShort();
				require(stream, count * 6);
				stream.skip(count * 6);
				return true;
			}
			case 2:
				require(stream, 2);
				stream.skip(2);
				return true;
			case 3:
			{
				require(stream, 1);
				int count = stream.readUnsignedByte();
				require(stream, count);
				stream.skip(count);
				return true;
			}
			case 4:
				return true;
			case 5:
			case 8:
			case 9:
			case 10:
			case 11:
			case 16:
				require(stream, 1);
				stream.skip(1);
				return true;
			case 6:
				require(stream, 2);
				result[0] = stream.readUnsignedShort();
				return true;
			case 7:
				require(stream, 2);
				result[1] = stream.readUnsignedShort();
				return true;
			case 12:
			{
				require(stream, 1);
				int count = stream.readUnsignedByte();
				require(stream, count * 4);
				stream.skip(count * 4);
				return true;
			}
			case 13:
				require(stream, 4);
				stream.skip(4);
				return true;
			case 14:
			{
				require(stream, 2);
				int count = stream.readUnsignedShort();
				require(stream, count * 8);
				stream.skip(count * 8);
				return true;
			}
			case 15:
				require(stream, 4);
				stream.skip(4);
				return true;
			case 17:
			{
				require(stream, 1);
				int count = stream.readUnsignedByte();
				require(stream, count);
				stream.skip(count);
				return true;
			}
			case 18:
			{
				while (true)
				{
					require(stream, 1);
					if (stream.readUnsignedByte() == 0)
					{
						return true;
					}
				}
			}
			case 19:
				return true;
			default:
				log.debug("Unknown sequence opcode {}", opcode);
				return false;
		}
	}

	private static void require(InputStream stream, int bytes)
	{
		if (stream.remaining() < bytes)
		{
			throw new IndexOutOfBoundsException("Short sequence data");
		}
	}
}
