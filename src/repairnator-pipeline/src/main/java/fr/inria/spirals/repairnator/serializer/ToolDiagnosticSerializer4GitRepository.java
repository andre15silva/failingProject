package fr.inria.spirals.repairnator.serializer;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector;
import fr.inria.spirals.repairnator.serializer.engines.SerializedData;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;
import fr.inria.spirals.repairnator.utils.DateUtils;

public class ToolDiagnosticSerializer4GitRepository extends AbstractDataSerializer {
    public ToolDiagnosticSerializer4GitRepository(List<SerializerEngine> engines, ProjectInspector inspector) {
        super(engines, SerializerType.TOOL_DIAGNOSTIC, inspector);
    }

    private List<Object> serializeAsList(ProjectInspector inspector, String toolName, JsonElement jsonElement) {
    	List<Object> result = new ArrayList<>();
        result.add(DateUtils.formatCompleteDate(new Date()));
        result.add(inspector.getProjectIdToBeInspected());
        result.add(toolName);
        result.add(jsonElement.toString());
        return result;
    }

    private JsonElement serializeAsJson(ProjectInspector inspector, String toolName, JsonElement jsonElement) {
        
        JsonObject data = new JsonObject();
        data.addProperty("dateStr", DateUtils.formatCompleteDate(new Date()));
        this.addDate(data, "date", new Date());
        data.addProperty("repositoryId", inspector.getProjectIdToBeInspected());
        data.addProperty("toolname", toolName);
        data.add("diagnostic", jsonElement);
        return data;
    }

    @Override
    public void serialize() {
        List<SerializedData> data = new ArrayList<>();

        for (Map.Entry<String, JsonElement> toolDiag : inspector.getJobStatus().getToolDiagnostic().entrySet()) {
            SerializedData serializedData = new SerializedData(this.serializeAsList(inspector, toolDiag.getKey(), toolDiag.getValue()), this.serializeAsJson(inspector, toolDiag.getKey(), toolDiag.getValue()));
            data.add(serializedData);
        }

        if (!data.isEmpty()) {
            for (SerializerEngine engine : this.getEngines()) {
                engine.serialize(data, this.getType());
            }
        }
    }
}
