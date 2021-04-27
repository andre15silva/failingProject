package fr.inria.spirals.repairnator.realtime.serializer;

import com.google.gson.JsonObject;
import fr.inria.jtravis.entities.Repository;
import fr.inria.spirals.repairnator.utils.DateUtils;
import fr.inria.spirals.repairnator.utils.Utils;
import fr.inria.spirals.repairnator.realtime.RTScanner;
import fr.inria.spirals.repairnator.serializer.SerializerImpl;
import fr.inria.spirals.repairnator.serializer.SerializerType;
import fr.inria.spirals.repairnator.serializer.engines.SerializedData;
import fr.inria.spirals.repairnator.serializer.engines.SerializerEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class BlacklistedSerializer extends SerializerImpl {

    public enum Reason {
        OTHER_LANGUAGE,
        USE_GRADLE,
        UNKNOWN_BUILD_TOOL,
        NO_SUCCESSFUL_BUILD,
        CONFIGURED_AS_BLACKLISTED
    }

    RTScanner rtScanner;
    public BlacklistedSerializer(RTScanner rtScanner, SerializerEngine... engines) {
        super(Arrays.asList(engines), SerializerType.BLACKLISTED);
        this.rtScanner = rtScanner;
    }

    private List<Object> serializeAsList(Repository repo, Reason reason, String comment)  {
        List<Object> result = new ArrayList<>();
        result.add(Utils.getHostname());
        result.add(this.rtScanner.getRunId());
        result.add(DateUtils.formatCompleteDate(new Date()));
        result.add(repo.getId());
        result.add(repo.getSlug());
        result.add(reason.name());
        result.add(comment);
        return result;
    }

    private JsonObject serializeAsJson(Repository repo, Reason reason, String comment) {
        JsonObject result = new JsonObject();

        result.addProperty("hostname", Utils.getHostname());
        result.addProperty("runId", this.rtScanner.getRunId());
        this.addDate(result, "dateBlacklist", new Date());
        result.addProperty("dateBlacklistStr", DateUtils.formatCompleteDate(new Date()));
        result.addProperty("repoId", repo.getId());
        result.addProperty("repoName", repo.getSlug());
        result.addProperty("reason", reason.name());
        result.addProperty("comment", comment);

        return result;
    }

    List<SerializedData> allData = new ArrayList<>();

    public void addBlackListedRepo(Repository repo, Reason reason, String comment) {
        SerializedData data = new SerializedData(this.serializeAsList(repo, reason, comment), this.serializeAsJson(repo, reason, comment));

        allData.add(data);
    }

    @Override
    public void serialize() {
        for (SerializerEngine engine : this.getEngines()) {
            engine.serialize(allData, this.getType());
        }
    }
}
