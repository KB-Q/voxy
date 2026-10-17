package me.cortex.voxy.autotest;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Report {
    public String scenario;
    public String status = "RUNNING";
    public final List<String> failures = new ArrayList<>();
    public Map<String, Object> gl;
    public boolean voxyInstancePresent;
    public boolean voxyRendererPresent;
    public long storageBytesStart;
    public long storageBytesEnd;
    public int glErrors;
    public double durationSeconds;
    public final List<Map<String, Object>> viewpoints = new ArrayList<>();
    public List<String> voxyDebug = new ArrayList<>();
    public final List<Map<String, Object>> survey = new ArrayList<>();
    public final List<String> closedScreens = new ArrayList<>();

    public Map<String, Object> addViewpoint(String name) {
        var vp = new LinkedHashMap<String, Object>();
        vp.put("name", name);
        this.viewpoints.add(vp);
        return vp;
    }

    public void fail(String reason) {
        this.failures.add(reason);
    }

    public void write(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(this));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
