package org.qainsights.jmeter.ai.generate;

import java.util.ArrayList;
import java.util.List;

/** A test plan to be generated: a name and an ordered list of requests. */
public final class TestPlanModel {

    private String name;
    private final List<HttpRequestSpec> requests = new ArrayList<>();

    public TestPlanModel(String name) {
        this.name = (name == null || name.isEmpty()) ? "Generated Test Plan" : name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
    }

    public List<HttpRequestSpec> getRequests() {
        return requests;
    }

    public TestPlanModel add(HttpRequestSpec request) {
        if (request != null) {
            requests.add(request);
        }
        return this;
    }

    public boolean isEmpty() {
        return requests.isEmpty();
    }
}
