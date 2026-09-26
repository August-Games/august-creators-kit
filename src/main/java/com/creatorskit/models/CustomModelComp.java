package com.creatorskit.models;

import com.google.gson.annotations.SerializedName;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class CustomModelComp
{
    private CustomModelType type;
    private int modelId;
    private Integer widthScale;
    private Integer heightScale;
    private ModelStats[] modelStats;
    private int[] kitRecolours;
    private DetailedModel[] detailedModels;
    private BlenderModel blenderModel;
    private Integer renderMode;
    private CustomLighting customLighting;
    private boolean priority;
    private String name;

    /**
     * Optional agent-authored resolve request. When {@code needs_resolve} is
     * true the comp carries no usable geometry yet ({@code modelStats} is
     * null): the host must resolve it against the live cache before playback
     * (player: {@code equipment_slots}; NPC: {@code npc_id}) and refuse to
     * play while any request is unresolved. Absent (null) means no request.
     */
    @SerializedName("needs_resolve")
    private Boolean needsResolve;

    @SerializedName("resolve_type")
    private String resolveType;

    @SerializedName("equipment_slots")
    private Map<String, Integer> equipmentSlots;

    @SerializedName("npc_id")
    private Integer npcId;

    @SerializedName("female")
    private Boolean female;

    @SerializedName("gender")
    private String gender;

    /**
     * Backward-compatible constructor for the pre-resolve fields. New code
     * should use the full constructor and pass the resolve request through.
     */
    public CustomModelComp(
        CustomModelType type,
        int modelId,
        Integer widthScale,
        Integer heightScale,
        ModelStats[] modelStats,
        int[] kitRecolours,
        DetailedModel[] detailedModels,
        BlenderModel blenderModel,
        Integer renderMode,
        CustomLighting customLighting,
        boolean priority,
        String name)
    {
        this(type, modelId, widthScale, heightScale, modelStats, kitRecolours,
            detailedModels, blenderModel, renderMode, customLighting,
            priority, name, null, null, null, null, null, null);
    }

    /** True only when an explicit resolve request is present. */
    public boolean isResolveRequested()
    {
        return Boolean.TRUE.equals(needsResolve);
    }
}
