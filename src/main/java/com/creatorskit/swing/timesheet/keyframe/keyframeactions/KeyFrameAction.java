package com.creatorskit.swing.timesheet.keyframe.keyframeactions;

import com.creatorskit.swing.timesheet.keyframe.KeyFrame;
import com.creatorskit.swing.timesheet.keyframe.KeyFrameCategory;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KeyFrameAction
{
    private KeyFrameActionType actionType;
    private KeyFrameCategory category;
    private KeyFrame keyFrame;
}
